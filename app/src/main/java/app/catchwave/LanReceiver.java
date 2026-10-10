// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;
import android.media.*;
import android.os.SystemClock;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.security.*;
import java.security.cert.*;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.*;

/** One explicitly paired TLS session. No discovery, reconnect or disk audio. */
final class LanReceiver implements AutoCloseable {
    interface Listener {
        boolean ready();void connected();void finished();void ack(boolean accepted);
        default void stage(LanConnection.Stage stage){} default void failed(LanConnection.Error code,LanConnection.Stage stage){}
        default void policy(boolean allowed){} default byte[] report(){return new byte[]{1,0,0,0,0};}
    }
    private static final class Control {final int action;final long at,epoch,policyEpoch;Control(int action,long epoch,long policyEpoch){this.action=action;this.epoch=epoch;this.policyEpoch=policyEpoch;at=SystemClock.elapsedRealtime();}}
    private final LanSettings settings;private final Listener listener;
    private final ArrayBlockingQueue<byte[]> audio=new ArrayBlockingQueue<>(8);
    private final ArrayBlockingQueue<Control> controls=new ArrayBlockingQueue<>(4);
    private final AtomicBoolean active=new AtomicBoolean(true);
    private volatile SSLSocket socket;private volatile AudioTrack track;
    private volatile long latestSequence=-1,lastAudio,commandSequence;
    private volatile long receivedFrames,playedFrames;private final AtomicLong queueDrops=new AtomicLong();
    private volatile long maxArrivalGapMs;
    private volatile LanProtocol.Format format;
    private volatile LanConnection.Stage stage=LanConnection.Stage.TCP;
    private final AtomicBoolean failureReported=new AtomicBoolean();
    private volatile int outputState;
    private volatile boolean hostPolicy;
    private volatile long policyEpoch=-1;
    private final Object localControlLock=new Object();
    private final LanFreshStream stream=new LanFreshStream();
    private volatile LanTimeline timeline;private volatile boolean timelineArmed;
    private final long createdAt=SystemClock.elapsedRealtime();
    LanReceiver(LanSettings settings,Listener listener){this.settings=settings;this.listener=listener;}
    boolean fresh(){return active.get()&&format!=null&&latestSequence>=0&&stream.fresh(SystemClock.elapsedRealtime());}
    void send(int action){if(fresh()&&settings.hostControl()&&hostPolicy)controls.offer(new Control(action,settings.controlEpoch(),policyEpoch));}
    boolean commandAllowed(long epoch){return settings.hostControl()&&settings.controlEpoch()==epoch;}
    boolean commandAllowed(long epoch,long generation){return commandAllowed(epoch)&&hostPolicy&&policyEpoch==generation;}
    long policyGeneration(){return policyEpoch;}
    boolean localControlAllowed(){synchronized(localControlLock){return fresh()&&commandAllowed(settings.controlEpoch(),policyEpoch);}}
    boolean executeLocalControl(long epoch,long generation,Runnable adjustment){
        synchronized(localControlLock){
            if(!fresh()||!commandAllowed(epoch,generation))return false;
            adjustment.run();return true;
        }
    }
    void revokePending(){controls.clear();}
    void volume(float value){AudioTrack t=track;if(t!=null)t.setVolume(value);}
    private void stage(LanConnection.Stage value){stage=value;listener.stage(value);}
    private void fail(LanConnection.Error code,LanConnection.Stage at){if(active.get()&&failureReported.compareAndSet(false,true))listener.failed(code,at);}
    void policy(byte[] body)throws IOException {
        if(body.length!=9||body[0]<0||body[0]>1)throw new LanFailure.Coded(LanConnection.Error.PROTOCOL);
        boolean allowed;
        synchronized(localControlLock){
            long generation=ByteBuffer.wrap(body,1,8).getLong();if(generation<0||generation<policyEpoch)throw new LanFailure.Coded(LanConnection.Error.PROTOCOL);
            policyEpoch=generation;hostPolicy=body[0]==1;allowed=hostPolicy;if(!allowed)revokePending();
        }
        listener.policy(allowed);
    }
    void run(){
        try {
            byte[] expected=settings.pin();
            X509TrustManager trust=new X509TrustManager(){
                public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
                public void checkClientTrusted(X509Certificate[] chain,String auth)throws CertificateException {throw new CertificateException("Client certificate not used");}
                public void checkServerTrusted(X509Certificate[] chain,String auth)throws CertificateException {
                    if(chain.length!=1)throw new CertificateException("Invalid server certificate");chain[0].checkValidity();
                    try {if(!MessageDigest.isEqual(expected,MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded())))throw new LanFailure.PinMismatch();}
                    catch(NoSuchAlgorithmException e){throw new CertificateException(e);}
                }
            };
            SSLContext context=SSLContext.getInstance("TLS");context.init(null,new TrustManager[]{trust},null);
            SSLSocket s=(SSLSocket)context.getSocketFactory().createSocket();socket=s;if(!active.get())return;
            stage(LanConnection.Stage.TCP);s.connect(new InetSocketAddress(settings.host(),settings.port()),5000);s.setSoTimeout(5000);s.setTcpNoDelay(true);
            stage(LanConnection.Stage.TLS);s.setEnabledProtocols(new String[]{"TLSv1.2"});s.startHandshake();s.setSoTimeout(1500);
            DataInputStream in=new DataInputStream(s.getInputStream());DataOutputStream out=new DataOutputStream(s.getOutputStream());
            stage(LanConnection.Stage.AUTH);LanProtocol.write(out,LanProtocol.AUTH,settings.token());
            LanProtocol.Packet initial=LanProtocol.read(in);
            if(initial.kind!=LanProtocol.FORMAT)throw new LanFailure.Coded(LanConnection.Error.AUTH_REJECTED_OR_CLOSED);
            stage(LanConnection.Stage.AUTHENTICATED);format=new LanProtocol.Format(initial.body);stage(LanConnection.Stage.FORMAT);
            LanProtocol.Packet hostPolicy=LanProtocol.read(in);
            if(hostPolicy.kind!=LanProtocol.POLICY)throw new LanFailure.Coded(LanConnection.Error.PROTOCOL);policy(hostPolicy.body);
            LanProtocol.AudioGate gate=new LanProtocol.AudioGate();LanProtocol.Packet first=LanProtocol.firstAudio(in,this::policy);gate.accept(first.body,format.bytes());
            latestSequence=gate.sequence();lastAudio=SystemClock.elapsedRealtime();receivedFrames++;
            stream.received(lastAudio);timeline=new LanTimeline(ByteBuffer.wrap(first.body,8,8).getLong(),lastAudio);
            audio.offer(first.body);
            new Thread(this::initializePlayback,"CatchWave-output").start();
            new Thread(()->writeCommands(out),"CatchWave-commands").start();
            while(active.get()){
                LanProtocol.Packet packet=LanProtocol.read(in);
                if(packet.kind==LanProtocol.AUDIO){
                    gate.accept(packet.body,format.bytes());long stamp=ByteBuffer.wrap(packet.body,8,8).getLong(),now=SystemClock.elapsedRealtime();
                    if(timelineArmed&&timeline.stalled(stamp,now))throw new LanFailure.Coded(LanConnection.Error.AUDIO_STALLED);
                    maxArrivalGapMs=Math.max(maxArrivalGapMs,now-lastAudio);
                    latestSequence=gate.sequence();lastAudio=now;stream.received(now);receivedFrames++;
                    if(!audio.offer(packet.body)){audio.poll();audio.offer(packet.body);queueDrops.incrementAndGet();}
                }else if(packet.kind==LanProtocol.POLICY){policy(packet.body);
                }else if(packet.kind==LanProtocol.ACK){
                    if(packet.body.length!=9)throw new IOException("Invalid acknowledgment");
                    if(ByteBuffer.wrap(packet.body).getLong()==commandSequence)listener.ack(packet.body[8]==1);
                }else throw new IOException("Unexpected frame");
            }
        }catch(Exception failure){fail(LanFailure.classify(failure,stage),stage);}
        finally {close();listener.finished();}
    }
    private void initializePlayback(){
        boolean entered=false;
        try {
            stage(LanConnection.Stage.FOCUS);
            // Network reading continues while this worker waits for Android's main callback.
            if(!listener.ready())throw new LanFailure.Coded(LanConnection.Error.AUDIO_FOCUS);
            if(!active.get())return;stage(LanConnection.Stage.OUTPUT);
            int mask=format.channels==2?AudioFormat.CHANNEL_OUT_STEREO:AudioFormat.CHANNEL_OUT_MONO;
            int minimum=AudioTrack.getMinBufferSize(format.rate,mask,AudioFormat.ENCODING_PCM_16BIT);
            if(minimum<=0)throw new LanFailure.Coded(LanConnection.Error.PCM_OUTPUT);
            track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(format.rate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(minimum,format.bytes()*4)).build();
            if(track.getState()!=AudioTrack.STATE_INITIALIZED)throw new LanFailure.Coded(LanConnection.Error.PCM_OUTPUT);
            byte[] first=null;long deadline=SystemClock.elapsedRealtime()+2000;
            while(active.get()){
                if(SystemClock.elapsedRealtime()>deadline)throw new LanFailure.Coded(LanConnection.Error.AUDIO_STALLED);
                byte[] candidate=audio.poll(250,TimeUnit.MILLISECONDS);if(candidate==null)continue;
                long now=SystemClock.elapsedRealtime(),stamp=ByteBuffer.wrap(candidate,8,8).getLong();
                if(!timeline.startupFresh(stamp,now)){queueDrops.incrementAndGet();continue;}
                first=candidate;timeline.arm(stamp,now);timelineArmed=true;break;
            }
            if(first!=null&&active.get()){entered=true;playback(first);}
        }catch(Exception failure){fail(failure instanceof LanFailure.Coded?LanFailure.classify(failure,stage):LanConnection.Error.PCM_OUTPUT,stage);close();}
        finally {if(!entered){AudioTrack t=track;track=null;if(t!=null)t.release();}}
    }
    private void playback(byte[] first){
        long previous=-1;
        try {
            AudioTrack t=track;if(t==null)return;t.play();
            while(active.get()){
                byte[] frame=first;first=null;if(frame==null)frame=audio.poll(250,TimeUnit.MILLISECONDS);if(frame==null){if(!fresh())throw new LanFailure.Coded(LanConnection.Error.AUDIO_STALLED);continue;}
                long sequence=ByteBuffer.wrap(frame).getLong();
                if(previous>=0&&sequence!=previous+1){t.pause();t.flush();t.play();}previous=sequence;
                byte[] pcm=Arrays.copyOfRange(frame,16,frame.length);
                LanProtocol.writeFully((bytes,offset,count)->active.get()?t.write(bytes,offset,count,AudioTrack.WRITE_BLOCKING):-1,pcm);
                playedFrames++;if(playedFrames==1)listener.connected();
                boolean silence=true;for(byte b:pcm)if(b!=0){silence=false;break;}
                int value=silence?1:2;if(value!=outputState){outputState=value;stage(silence?LanConnection.Stage.SILENCE:LanConnection.Stage.STREAMING);}
            }
        }catch(Exception failure){fail(failure instanceof LanFailure.Coded?LanFailure.classify(failure,stage):LanConnection.Error.PCM_OUTPUT,LanConnection.Stage.OUTPUT);close();}
        finally {AudioTrack t=track;track=null;if(t!=null){try{t.pause();t.flush();}catch(RuntimeException ignored){}t.release();}}
    }
    private void writeCommands(DataOutputStream out){
        try {long reported=-1000;while(active.get()){
            long now=SystemClock.elapsedRealtime();
            if(now-reported>=500){byte[] report=Arrays.copyOf(listener.report(),13);report[1]=(byte)outputState;ByteBuffer.wrap(report,5,8).putLong(policyEpoch);LanProtocol.write(out,LanProtocol.CLIENT_STATE,report);reported=now;}
            Control control=controls.poll(250,TimeUnit.MILLISECONDS);if(control==null)continue;
            if(!fresh()||SystemClock.elapsedRealtime()-control.at>500||!commandAllowed(control.epoch,control.policyEpoch))continue;
            LanProtocol.write(out,LanProtocol.COMMAND,LanProtocol.command(format.session,++commandSequence,latestSequence,control.action,control.policyEpoch));
        }}catch(Exception failure){fail(LanFailure.classify(failure,stage),stage);close();}
    }
    public void close(){active.set(false);audio.clear();controls.clear();SSLSocket s=socket;if(s!=null)try{s.close();}catch(IOException ignored){}}
    String statistics(){int queued=audio.size();return "Сеанс: "+((SystemClock.elapsedRealtime()-createdAt)/1000)+" с · получено "+receivedFrames+" · записано в AudioTrack "+playedFrames+"\nОчередь PCM в RAM: "+queued+" кадров ("+(queued*20)+" мс) · отброшено "+queueDrops.get()+"\nМакс. интервал прихода: "+maxArrivalGapMs+" мс (не задержка от ПК до динамика)";}
    void dump(PrintWriter out){
        LanProtocol.Format f=format;
        out.println("audioFresh="+fresh()+" receivedFrames="+receivedFrames+" playedFrames="+playedFrames+" queueDrops="+queueDrops.get());
        out.println("lastSequence="+latestSequence+" audioAgeMs="+(latestSequence<0?-1:SystemClock.elapsedRealtime()-lastAudio)+" sentCommands="+commandSequence);
        AudioTrack t=track;int underruns=-1;
        AudioDeviceInfo routed=t==null?null:t.getRoutedDevice();
        out.println("routedDeviceType="+(routed==null?-1:routed.getType()));
        if(t!=null)try{underruns=t.getUnderrunCount();}catch(IllegalStateException ignored){}
        out.println("maxArrivalGapMs="+maxArrivalGapMs+" audioTrackUnderruns="+underruns);
        if(f!=null)out.println("sampleRate="+f.rate+" channels="+f.channels);
    }
}
