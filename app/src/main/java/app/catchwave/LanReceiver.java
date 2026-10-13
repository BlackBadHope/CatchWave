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
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.*;

/** One explicitly paired TLS session. No discovery, reconnect or disk audio. */
final class LanReceiver implements AutoCloseable {
    interface Listener {
        boolean ready();void connected();void finished();void ack(boolean accepted);
        default void stage(LanConnection.Stage stage){} default void failed(LanConnection.Error code,LanConnection.Stage stage){}
        default void policy(boolean allowed){} default byte[] report(){return new byte[]{1,0,0,0,0};}
    }
    private static final class Control {final int action;final long at,epoch,policyEpoch,armEpoch;Control(int action,long epoch,long policyEpoch,long armEpoch){this.action=action;this.epoch=epoch;this.policyEpoch=policyEpoch;this.armEpoch=armEpoch;at=SystemClock.elapsedRealtime();}}
    private final LanSettings settings;private final Listener listener;
    private final java.util.function.BooleanSupplier authority;private final java.util.function.LongSupplier authorityEpoch;
    private final ArrayBlockingQueue<byte[]> audio=new ArrayBlockingQueue<>(8);
    private final ArrayBlockingQueue<Control> controls=new ArrayBlockingQueue<>(4);
    private final AtomicBoolean active=new AtomicBoolean(true);
    private final Object outputLock=new Object();private volatile boolean focusPaused;private long outputEpoch;
    private volatile long focusDrops;
    private final AtomicReference<SSLSocket> socket=new AtomicReference<>();private volatile AudioTrack track;
    private volatile long latestSequence=-1,lastAudio,commandSequence;
    private volatile long receivedFrames,playedFrames;private final AtomicLong queueDrops=new AtomicLong(),lateDrops=new AtomicLong();
    private volatile long maxArrivalGapMs;
    private volatile long lastReadExcessMs,playbackResyncs;
    private final AtomicLong lateReadDrops=new AtomicLong(),latePlaybackDrops=new AtomicLong();
    private volatile LanProtocol.Format format;
    private LanControlIdentity controlIdentity;
    private volatile LanConnection.Stage stage=LanConnection.Stage.TCP;
    private final AtomicBoolean failureReported=new AtomicBoolean();
    private volatile int outputState;
    private volatile boolean hostPolicy;
    private volatile long policyEpoch=-1;
    private final Object localControlLock=new Object();
    private final LanFreshStream stream=new LanFreshStream();
    private final PacketRate policyRate=new PacketRate(64);
    private volatile LanTimeline timeline;private volatile boolean timelineArmed;
    private final long createdAt=SystemClock.elapsedRealtime();
    LanReceiver(LanSettings settings,Listener listener){this(settings,listener,()->true,()->0);}
    LanReceiver(LanSettings settings,Listener listener,java.util.function.BooleanSupplier authority,java.util.function.LongSupplier authorityEpoch){this.settings=settings;this.listener=listener;this.authority=authority;this.authorityEpoch=authorityEpoch;}
    boolean fresh(){return active.get()&&format!=null&&latestSequence>=0&&stream.fresh(SystemClock.elapsedRealtime());}
    boolean failurePending(){return failureReported.get();}
    boolean playbackAlive(){return active.get()&&format!=null&&latestSequence>=0&&!stream.timedOut(SystemClock.elapsedRealtime());}
    boolean focusPaused(){return focusPaused;}
    void waitingForAudio(){if(active.get()&&!focusPaused&&outputState!=0){outputState=0;controls.clear();stage(LanConnection.Stage.BUFFERING);}}
    void send(int action){if(!focusPaused&&fresh()&&settings.hostControl()&&hostPolicy&&authority.getAsBoolean())controls.offer(new Control(action,settings.controlEpoch(),policyEpoch,authorityEpoch.getAsLong()));}
    boolean commandAllowed(long epoch){return settings.hostControl()&&settings.controlEpoch()==epoch;}
    boolean commandAllowed(long epoch,long generation){return commandAllowed(epoch)&&hostPolicy&&policyEpoch==generation;}
    boolean commandArmed(long bornRevision){return authority.getAsBoolean()&&bornRevision==authorityEpoch.getAsLong();}
    long policyGeneration(){return policyEpoch;}
    boolean hostPermitted(){return hostPolicy;}
    boolean localControlAllowed(){synchronized(localControlLock){return !focusPaused&&fresh()&&commandAllowed(settings.controlEpoch(),policyEpoch);}}
    boolean executeLocalControl(long epoch,long generation,Runnable adjustment){
        synchronized(localControlLock){
            if(focusPaused||!fresh()||!commandAllowed(epoch,generation))return false;
            adjustment.run();return true;
        }
    }
    void revokePending(){controls.clear();}
    void volume(float value){AudioTrack t=track;if(t!=null)t.setVolume(value);}
    private void stage(LanConnection.Stage value){if(focusPaused&&(value==LanConnection.Stage.OUTPUT||value==LanConnection.Stage.BUFFERING||value==LanConnection.Stage.SILENCE||value==LanConnection.Stage.STREAMING))value=LanConnection.Stage.PHONE_AUDIO;stage=value;DiagnosticStore.LOG.event(DiagnosticLog.Event.STAGE,value.name());listener.stage(value);}
    private void fail(LanConnection.Error code,LanConnection.Stage at){if(active.get()&&failureReported.compareAndSet(false,true)){DiagnosticStore.LOG.event(DiagnosticLog.Event.STOP,code.name(),at.ordinal());listener.failed(code,at);}}
    void policy(byte[] body)throws IOException {
        if(body.length!=9||body[0]<0||body[0]>1)throw new LanFailure.Coded(LanConnection.Error.PROTOCOL);
        policyRate.accept(SystemClock.elapsedRealtime());
        boolean allowed;
        synchronized(localControlLock){
            long generation=ByteBuffer.wrap(body,1,8).getLong();if(generation<0||generation<policyEpoch)throw new LanFailure.Coded(LanConnection.Error.PROTOCOL);
            policyEpoch=generation;hostPolicy=body[0]==1;allowed=hostPolicy;if(!allowed)revokePending();
        }
        listener.policy(allowed);
    }
    void run(){
        try {
            if(!active.get())return;
            // First-use hardware key generation must not consume the Host AUTH deadline.
            controlIdentity=new LanControlIdentity();if(!active.get())return;
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
            SSLSocket s=(SSLSocket)context.getSocketFactory().createSocket();if(!publishSocket(s))return;
            LanNetwork.bind(settings.context,s,settings.host());if(!active.get())return;
            stage(LanConnection.Stage.TCP);s.connect(new InetSocketAddress(settings.host(),settings.port()),5000);s.setSoTimeout(5000);s.setTcpNoDelay(true);
            stage(LanConnection.Stage.TLS);s.setEnabledProtocols(new String[]{"TLSv1.2"});s.startHandshake();s.setSoTimeout(1500);
            DataInputStream in=new DataInputStream(s.getInputStream());DataOutputStream out=new DataOutputStream(s.getOutputStream());
            stage(LanConnection.Stage.AUTH);LanProtocol.write(out,LanProtocol.AUTH,controlIdentity.authenticate(settings.token()));
            LanProtocol.Packet initial=LanProtocol.read(in);
            if(initial.kind!=LanProtocol.FORMAT)throw new LanFailure.Coded(LanConnection.Error.AUTH_REJECTED_OR_CLOSED);
            stage(LanConnection.Stage.AUTHENTICATED);format=new LanProtocol.Format(initial.body);DiagnosticStore.LOG.session(format.session);stage(LanConnection.Stage.FORMAT);
            LanProtocol.Packet hostPolicy=LanProtocol.read(in);
            if(hostPolicy.kind!=LanProtocol.POLICY)throw new LanFailure.Coded(LanConnection.Error.PROTOCOL);policy(hostPolicy.body);
            LanProtocol.AudioGate gate=new LanProtocol.AudioGate();LanProtocol.Packet first=LanProtocol.firstAudio(in,this::policy);gate.accept(first.body,format.bytes());
            latestSequence=gate.sequence();lastAudio=SystemClock.elapsedRealtime();receivedFrames++;
            stream.received(lastAudio);timeline=new LanTimeline(ByteBuffer.wrap(first.body,8,8).getLong(),lastAudio);
            audio.offer(first.body);
            s.setSoTimeout(LanFreshStream.RECOVERY_MS);
            new Thread(this::initializePlayback,"CatchWave-output").start();
            new Thread(()->writeCommands(out),"CatchWave-commands").start();
            while(active.get()){
                LanProtocol.Packet packet=LanProtocol.read(in);
                if(packet.kind==LanProtocol.AUDIO){
                    receiveAudio(packet,gate,SystemClock.elapsedRealtime());
                }else if(packet.kind==LanProtocol.POLICY){policy(packet.body);
                }else if(packet.kind==LanProtocol.ACK){
                    if(packet.body.length!=9)throw new IOException("Invalid acknowledgment");
                    if(ByteBuffer.wrap(packet.body).getLong()==commandSequence){DiagnosticStore.LOG.event(DiagnosticLog.Event.ACK,packet.body[8]==1?"ACCEPTED":"REJECTED",commandSequence);listener.ack(packet.body[8]==1);}
                }else throw new IOException("Unexpected frame");
            }
        }catch(Exception failure){if(failure instanceof SSLException)DiagnosticStore.LOG.event(DiagnosticLog.Event.STOP,LanFailure.tlsReason(failure));fail(LanFailure.classify(failure,stage),stage);}
        finally {close();listener.finished();}
    }
    boolean receiveAudio(LanProtocol.Packet packet,LanProtocol.AudioGate gate,long now)throws IOException{
        if(!active.get())return false;
        gate.accept(packet.body,format.bytes());receivedFrames++;
        long stamp=ByteBuffer.wrap(packet.body,8,8).getLong();
        // The ordered TLS reader drains delayed packets, but they must not refresh control
        // authority or reach AudioTrack. A fresh packet can recover the same connection.
        if(timelineArmed){
            timeline.observe(stamp,now);lastReadExcessMs=timeline.excessAge(stamp,now);
            if(timeline.stalled(stamp,now)){lateDrops.incrementAndGet();lateReadDrops.incrementAndGet();return false;}
        }
        maxArrivalGapMs=Math.max(maxArrivalGapMs,now-lastAudio);
        latestSequence=gate.sequence();lastAudio=now;stream.received(now);
        if(!audio.offer(packet.body)){audio.poll();audio.offer(packet.body);queueDrops.incrementAndGet();}
        return true;
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
            if(first!=null&&active.get()){entered=true;listener.connected();playback(first);}
        }catch(Exception failure){fail(failure instanceof LanFailure.Coded?LanFailure.classify(failure,stage):LanConnection.Error.PCM_OUTPUT,stage);close();}
        finally {if(!entered)synchronized(outputLock){AudioTrack t=track;track=null;if(t!=null)t.release();}}
    }
    private void playback(byte[] first){
        long previous=-1,seenOutput=-1;boolean paused=false;
        try {
            AudioTrack t=track;if(t==null)return;
            while(active.get()){
                byte[] frame=first;first=null;if(frame==null)frame=audio.poll(250,TimeUnit.MILLISECONDS);
                if(frame==null){
                    if(!playbackAlive())throw new LanFailure.Coded(LanConnection.Error.AUDIO_STALLED);
                    if(!fresh()&&!paused){t.pause();t.flush();paused=true;previous=-1;waitingForAudio();}
                    continue;
                }
                if(focusPaused){focusDrops++;previous=-1;paused=true;continue;}
                long now=SystemClock.elapsedRealtime(),stamp=ByteBuffer.wrap(frame,8,8).getLong();
                if(timeline.stalled(stamp,now)){
                    lateDrops.incrementAndGet();latePlaybackDrops.incrementAndGet();waitingForAudio();
                    if(!paused){t.pause();t.flush();paused=true;previous=-1;}
                    continue;
                }
                long sequence=ByteBuffer.wrap(frame).getLong();
                // An isolated missing frame must not discard sound already buffered in AudioTrack.
                // Large discontinuities still drop the old output and resume at the live frame.
                long epoch;
                synchronized(outputLock){
                    if(focusPaused){focusDrops++;paused=true;previous=-1;continue;}
                    epoch=outputEpoch;
                    if(seenOutput<0&&epoch==0&&!paused)t.play();
                    else if(seenOutput!=epoch||paused||previous>=0&&sequence-previous>6){t.pause();t.flush();t.play();paused=false;playbackResyncs++;}
                    seenOutput=epoch;
                }
                byte[] pcm=Arrays.copyOfRange(frame,16,frame.length);
                if(!writePcm(t,pcm,epoch)){previous=-1;paused=true;continue;}
                previous=sequence;playedFrames++;
                boolean silence=true;for(byte b:pcm)if(b!=0){silence=false;break;}
                int value=silence?1:2;if(value!=outputState){outputState=value;stage(silence?LanConnection.Stage.SILENCE:LanConnection.Stage.STREAMING);}
            }
        }catch(Exception failure){fail(failure instanceof LanFailure.Coded?LanFailure.classify(failure,stage):LanConnection.Error.PCM_OUTPUT,LanConnection.Stage.OUTPUT);close();}
        finally {synchronized(outputLock){AudioTrack t=track;track=null;if(t!=null){try{t.pause();t.flush();}catch(RuntimeException ignored){}t.release();}}}
    }
    private boolean writePcm(AudioTrack t,byte[] pcm,long epoch)throws IOException,InterruptedException{
        int offset=0;long deadline=SystemClock.elapsedRealtime()+750;
        while(offset<pcm.length){
            int count;
            synchronized(outputLock){
                if(!active.get()||focusPaused||epoch!=outputEpoch){focusDrops++;return false;}
                count=t.write(pcm,offset,pcm.length-offset,AudioTrack.WRITE_NON_BLOCKING);
            }
            if(count<0||count>pcm.length-offset)throw new LanFailure.Coded(LanConnection.Error.PCM_OUTPUT);
            offset+=count;
            if(SystemClock.elapsedRealtime()>=deadline){lateDrops.incrementAndGet();latePlaybackDrops.incrementAndGet();return false;}
            if(count==0)Thread.sleep(5);
        }
        return true;
    }
    private void writeCommands(DataOutputStream out){
        try {long reported=-1000;while(active.get()){
            long now=SystemClock.elapsedRealtime();
            if(now-reported>=500){byte[] report=Arrays.copyOf(listener.report(),13);report[1]=(byte)outputState;ByteBuffer.wrap(report,5,8).putLong(policyEpoch);LanProtocol.write(out,LanProtocol.CLIENT_STATE,report);reported=now;}
            Control control=controls.poll(250,TimeUnit.MILLISECONDS);if(control==null)continue;
            if(focusPaused||!fresh()||SystemClock.elapsedRealtime()-control.at>500||!commandAllowed(control.epoch,control.policyEpoch)||!commandArmed(control.armEpoch)){DiagnosticStore.LOG.event(DiagnosticLog.Event.CONTROL,"QUEUE_REJECTED",control.action);continue;}
            DiagnosticStore.LOG.event(DiagnosticLog.Event.CONTROL,"SENT",control.action,commandSequence+1);
            byte[] command=controlIdentity.sign(LanProtocol.command(format.session,++commandSequence,latestSequence,control.action,control.policyEpoch));
            if(focusPaused||!commandAllowed(control.epoch,control.policyEpoch)||!commandArmed(control.armEpoch))continue;
            LanProtocol.write(out,LanProtocol.COMMAND,command);
        }}catch(Exception failure){fail(LanFailure.classify(failure,stage),stage);close();}
    }
    private boolean publishSocket(SSLSocket value){
        socket.set(value);
        if(!active.get()){close();return false;}
        return true;
    }
    public void close(){
        if(active.getAndSet(false))DiagnosticStore.LOG.event(DiagnosticLog.Event.STOP,"RECEIVER_CLOSED",receivedFrames,playedFrames);
        audio.clear();controls.clear();
        SSLSocket value=socket.getAndSet(null);
        if(value!=null)new Thread(()->{
            // Conscrypt may send TLS shutdown data from close(). Service teardown runs on
            // Android's main thread, so cancellation must return before this network work.
            try{value.close();}catch(IOException|RuntimeException ignored){DiagnosticStore.LOG.event(DiagnosticLog.Event.STOP,"TLS_CLOSE_FAILED");}
        },"CatchWave-TLS-close").start();
    }
    private int lastRoute=-2;private long lastDiagnostic=-10000;private boolean focusLost;
    void focus(int change){
        if(!active.get())return;
        boolean lost=change==AudioManager.AUDIOFOCUS_LOSS||change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT||change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK;
        synchronized(outputLock){
            focusLost=lost;
            if(focusPaused!=lost){focusPaused=lost;outputEpoch++;audio.clear();controls.clear();
                AudioTrack t=track;if(t!=null&&lost){t.pause();t.flush();}
            }
            if(lost)outputState=0;
        }
        DiagnosticStore.LOG.event(DiagnosticLog.Event.FOCUS,lost?"PHONE_AUDIO_PAUSED":"PC_AUDIO_RESUMED",change);
        if(lost)stage(LanConnection.Stage.PHONE_AUDIO);
    }
    String diagnostic(int volume){
        AudioTrack t=track;int type=-1,underruns=-1,play=-1;
        if(t!=null)try{AudioDeviceInfo route=t.getRoutedDevice();type=route==null?-1:route.getType();underruns=t.getUnderrunCount();play=t.getPlayState();}catch(IllegalStateException ignored){}
        if(type!=lastRoute){lastRoute=type;DiagnosticStore.LOG.event(DiagnosticLog.Event.ROUTE,type<0?"UNKNOWN":"CHANGED",type);}
        String code=focusPaused?"PHONE_AUDIO":!fresh()&&playbackAlive()?"BUFFERING":DiagnosticLog.output(fresh(),outputState==1,volume,focusLost,type);long now=SystemClock.elapsedRealtime();
        if(now-lastDiagnostic>=2000){lastDiagnostic=now;DiagnosticStore.LOG.event(DiagnosticLog.Event.SNAPSHOT,code,receivedFrames,playedFrames,latestSequence,latestSequence<0?-1:now-lastAudio,queueDrops.get(),underruns,type,volume,play,outputState,lateDrops.get(),lastReadExcessMs);}
        return switch(code){case "PHONE_AUDIO"->"Приоритет звука телефона. Звук ПК вернётся автоматически; соединение сохранено.";case "BUFFERING"->"Ожидается свежий звук; соединение восстанавливает поток до 5 с";case "STOPPED_STREAM"->"Поток остановлен/устарел";case "FOCUS_LOST"->"Потерян аудиофокус";case "ROUTE_UNKNOWN"->"Выход телефона не подтверждён";case "VOLUME_ZERO"->"Громкость телефона равна нулю";case "SILENT_SOURCE"->"Источник передаёт нулевые PCM-отсчёты";default->"AudioTrack принимает ненулевой PCM; слышимость не подтверждена";};
    }
    String statistics(){int queued=audio.size();return "Сеанс: "+((SystemClock.elapsedRealtime()-createdAt)/1000)+" с · получено "+receivedFrames+" · записано в AudioTrack "+playedFrames+"\nОчередь PCM в RAM: "+queued+" кадров ("+(queued*20)+" мс) · отброшено "+queueDrops.get()+" · устарело "+lateDrops.get()+"\nМакс. интервал прихода: "+maxArrivalGapMs+" мс (не задержка от ПК до динамика)";}
    void dump(PrintWriter out){
        LanProtocol.Format f=format;
        out.println("audioFresh="+fresh()+" playbackAlive="+playbackAlive()+" receivedFrames="+receivedFrames+" playedFrames="+playedFrames+" queueDrops="+queueDrops.get()+" lateDrops="+lateDrops.get());
        out.println("focusPaused="+focusPaused+" focusDroppedFrames="+focusDrops);
        out.println("lateReadDrops="+lateReadDrops.get()+" latePlaybackDrops="+latePlaybackDrops.get()+" lastReadExcessMs="+lastReadExcessMs+" clockCorrectionMs="+(timeline==null?0:timeline.clockCorrection())+" playbackResyncs="+playbackResyncs);
        out.println("lastSequence="+latestSequence+" audioAgeMs="+(latestSequence<0?-1:SystemClock.elapsedRealtime()-lastAudio)+" sentCommands="+commandSequence);
        AudioTrack t=track;int underruns=-1;
        AudioDeviceInfo routed=t==null?null:t.getRoutedDevice();
        out.println("routedDeviceType="+(routed==null?-1:routed.getType()));
        if(t!=null)try{underruns=t.getUnderrunCount();}catch(IllegalStateException ignored){}
        out.println("maxArrivalGapMs="+maxArrivalGapMs+" audioTrackUnderruns="+underruns);
        if(f!=null)out.println("sampleRate="+f.rate+" channels="+f.channels);
    }
}
