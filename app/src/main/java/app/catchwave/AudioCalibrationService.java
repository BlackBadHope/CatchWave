package app.catchwave;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.media.projection.*;
import android.media.session.*;
import android.os.*;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Audio-only, in-memory capture. No virtual display, video capture, files or network. */
public final class AudioCalibrationService extends Service {
    public static final String START="app.catchwave.AUDIO_CALIBRATE",STOP="app.catchwave.AUDIO_CANCEL";
    private final SessionModel model=SessionModel.INSTANCE;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private MediaProjection projection;
    private CaptureVolume volume;
    private Thread worker;
    private MediaController controller;
    private CalibrationTarget target;
    private PowerManager.WakeLock wake;
    private long deadline;
    private boolean ownsMeasurement;
    private final MediaProjection.Callback revoked=new MediaProjection.Callback(){@Override public void onStop(){cancelled.set(true);if(worker!=null)worker.interrupt();}};

    @Override public int onStartCommand(Intent intent,int flags,int startId){L.init(this);
        if(intent==null||STOP.equals(intent.getAction())){cancelled.set(true);if(worker!=null)worker.interrupt();else stopSelf();return START_NOT_STICKY;}
        if(worker!=null)return START_NOT_STICKY;
        Intent consent=Build.VERSION.SDK_INT>=33?intent.getParcelableExtra("consent",Intent.class):intent.getParcelableExtra("consent");
        controller=new MediaBridge(this).controller();target=new CalibrationTarget(controller,model.track);
        if(consent==null||intent.getIntExtra("result",0)!=Activity.RESULT_OK||!target.valid(controller,model)){
            model.record(L.t("Sound refinement: no permission, or the selected track has already changed"));stopSelf();return START_NOT_STICKY;
        }
        try{
            NotificationManager nm=getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("audio_alignment",L.t("Sound refinement"),NotificationManager.IMPORTANCE_LOW));
            PendingIntent open=PendingIntent.getActivity(this,21,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent stop=PendingIntent.getService(this,22,new Intent(this,AudioCalibrationService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE);
            Notification notification=new Notification.Builder(this,"audio_alignment").setSmallIcon(R.drawable.ic_wave).setContentTitle(L.t("Comparing audio"))
                .setContentText(L.t("Microphone and YouTube Music internal audio · up to 30 seconds")).setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(null,L.t("Cancel"),stop).build()).build();
            startForeground(21,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE|ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            projection=getSystemService(MediaProjectionManager.class).getMediaProjection(Activity.RESULT_OK,consent);
            if(projection==null)throw new IllegalStateException(L.t("Android did not grant audio capture"));
            projection.registerCallback(revoked,main);
            volume=new CaptureVolume(getSystemService(AudioManager.class));
            if(!volume.mute())throw new IllegalStateException(L.t("Could not mute the phone speaker"));
            wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"CatchWave:audioCompare");wake.acquire(35000);
            ownsMeasurement=true;model.measuringAudio=true;model.audioLagMs=Double.NaN;model.audioVerified=false;
            model.update(L.t("Comparing audio · speaker muted"),L.t("The external music must keep playing. The recording stays in the phone's memory."));
            deadline=SystemClock.elapsedRealtime()+30000;
            worker=new Thread(this::measure,"CatchWave-audio-compare");worker.start();
        }catch(Exception failure){model.update(L.t("Sound refinement unavailable"),message(failure));stopSelf();}
        return START_NOT_STICKY;
    }
    private void measure(){
        String result=L.t("Measurement cancelled");
        try{
            int uid=getPackageManager().getApplicationInfo(MediaBridge.PACKAGE,0).uid;
            AudioCorrection correction=new AudioCorrection();
            boolean storedLag=false;
            model.record(L.t("Refine by sound: capture started; seek_lag=")+(model.seekLagMs==Long.MIN_VALUE?"—":Long.toString(model.seekLagMs)));
            Thread.sleep(350);
            for(int pass=0;pass<3;pass++){
                check();
                if(CalibrationTarget.duration(controller)-CalibrationTarget.position(controller)<9000)throw new IllegalStateException(L.t("Too little time left in the recording — try again on the next song"));
                Capture[] pair=capture(uid);
                check();
                AudioAlignment.Estimate estimate=AudioAlignment.compare(pair[0].data,pair[0].anchor,pair[1].data,pair[1].anchor);
                model.record(String.format(Locale.ROOT,L.t("Audio measurement %d: refRms=%.5f micRms=%.5f anchors=%d/%d lag=%.2f ms confidence=%.2f windows=%.2f ms valid=%s"),pass+1,rms(pair[0].data),rms(pair[1].data),pair[0].anchor,pair[1].anchor,estimate.lagMs,estimate.confidence,estimate.windowDifferenceMs,estimate.valid));
                if(!estimate.valid){
                    if(rms(pair[0].data)<.001)throw new IllegalStateException(L.t("Internal audio is unavailable or too quiet. YouTube Music or Android may limit capture at zero volume."));
                    if(rms(pair[1].data)<.0008)throw new IllegalStateException(L.t("The microphone can barely hear the external music. Move the phone closer."));
                    if(pass==2)throw new IllegalStateException(L.t("Two fragments did not give an unambiguous match. Check the song version and continuous playback."));
                    model.record(L.t("Refine by sound: window ambiguous, retrying"));
                    continue;
                }
                model.audioLagMs=estimate.lagMs;
                if(!storedLag){
                    int next=(int)Math.max(-1200,Math.min(1200,getSharedPreferences("settings",MODE_PRIVATE).getInt("audio_lag_ms",0)+Math.round(estimate.lagMs)));
                    getSharedPreferences("settings",MODE_PRIVATE).edit().putInt("audio_lag_ms",next).apply();
                    storedLag=true;
                    model.record(L.t("Saved acoustic correction ")+next+L.t(" ms for the next handoff"));
                }
                if(Math.abs(estimate.lagMs)<=15){model.audioVerified=true;result=String.format(Locale.ROOT,L.t("Internal audio matched: %+.1f ms. The speaker's own latency is not included."),estimate.lagMs);break;}
                if(pass==2){result=String.format(Locale.ROOT,L.t("After two corrections %+.1f ms remain. An exact match was not confirmed; further seeking stopped."),estimate.lagMs);break;}
                long advance=correction.nextAdvance(estimate.lagMs),position=CalibrationTarget.position(controller),to=position+advance;
                if(position<0||to<0||to>=CalibrationTarget.duration(controller)-9000)throw new IllegalStateException(L.t("The correction falls outside the available recording"));
                check();
                long requested=SystemClock.elapsedRealtime();controller.getTransportControls().seekTo(to);
                model.record(L.t("Audio correction: ")+advance+L.t(" ms; seekTo=")+to);
                model.audioLagMs=Double.NaN;
                boolean acknowledged=false;
                long settledSession=-1,sessionUpdateAt=-1;
                for(int attempt=0;attempt<20;attempt++){
                    Thread.sleep(100);check();PlaybackState p=controller.getPlaybackState();
                    long session=CalibrationTarget.position(controller);
                    if(p.getLastPositionUpdateTime()>=requested&&Math.abs(session-(to+SystemClock.elapsedRealtime()-requested))<=180){acknowledged=true;settledSession=session;sessionUpdateAt=p.getLastPositionUpdateTime();break;}
                }
                if(!acknowledged)throw new IllegalStateException(L.t("YouTube Music did not confirm the seek. Not applying another correction."));
                long tEst=model.track==null?-1:model.track.positionAt(SystemClock.elapsedRealtime(),0);
                long lag=SeekClock.lag(requested,sessionUpdateAt);
                model.record("t_est="+tEst+" t_session_after_seek="+settledSession+" Δ="+(tEst<0?"—":Long.toString(SeekClock.delta(tEst,settledSession)))+" seek_lag="+lag);
                Thread.sleep(650);
            }
        }catch(InterruptedException ignored){result=L.t("Measurement cancelled. Volume restored unless you changed it.");}
        catch(Exception failure){result=message(failure);}
        finally{
            String detail=result;
            main.post(()->{
                boolean owned=ownsMeasurement;
                cleanup();
                if(owned){model.record(L.t("Refine by sound: result verified=")+model.audioVerified+" lag="+(Double.isFinite(model.audioLagMs)?String.format(Locale.ROOT,"%.1f",model.audioLagMs):"—")+" · "+detail);
                    if(model.running&&model.guardingTrack)model.update(model.audioVerified?L.t("Audio matched"):L.t("Refinement complete"),detail);}
                stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
            });
        }
    }
    private void check()throws InterruptedException{
        if(cancelled.get()||Thread.currentThread().isInterrupted())throw new InterruptedException();
        if(SystemClock.elapsedRealtime()>deadline)throw new IllegalStateException(L.t("The 30-second measurement limit was reached"));
        if(!target.valid(controller,model))throw new IllegalStateException(L.t("Playback changed. Refinement stopped so another recording is not seeked."));
        if(!volume.muted())throw new IllegalStateException(L.t("Volume changed manually. Refinement stopped."));
    }
    private Capture[] capture(int uid)throws Exception{
        if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new SecurityException(L.t("Microphone permission is off"));
        AudioFormat format=new AudioFormat.Builder().setSampleRate(16000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
        int buffer=Math.max(32000,AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)*4);
        AudioRecord reference=null,mic=null;
        try{
            AudioPlaybackCaptureConfiguration config=new AudioPlaybackCaptureConfiguration.Builder(projection).addMatchingUid(uid).addMatchingUsage(AudioAttributes.USAGE_MEDIA).build();
            reference=new AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(buffer).setAudioPlaybackCaptureConfig(config).build();
            mic=new AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(buffer).setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION).build();
            for(AudioDeviceInfo device:getSystemService(AudioManager.class).getDevices(AudioManager.GET_DEVICES_INPUTS))if(device.getType()==AudioDeviceInfo.TYPE_BUILTIN_MIC){mic.setPreferredDevice(device);break;}
            reference.startRecording();mic.startRecording();
            Capture[] captures={new Capture(reference),new Capture(mic)};
            long end=SystemClock.elapsedRealtime()+8500;
            while(!captures[0].done()||!captures[1].done()){
                check();if(SystemClock.elapsedRealtime()>end)throw new IllegalStateException(L.t("Android did not return both audio streams in time"));
                for(Capture capture:captures)capture.read();
                Thread.sleep(8);
            }
            return captures;
        }finally{release(reference);release(mic);}
    }
    private static void release(AudioRecord record){if(record!=null){try{record.stop();}catch(IllegalStateException ignored){}record.release();}}
    private static final class Capture {
        final AudioRecord record;final short[] data=new short[96000],buffer=new short[3200];int samples;long anchor;
        Capture(AudioRecord record){this.record=record;}
        boolean done(){return samples>=104000;}
        void read(){
            if(done())return;
            int count=record.read(buffer,0,Math.min(buffer.length,104000-samples),AudioRecord.READ_NON_BLOCKING);
            if(count<0)throw new IllegalStateException(L.t("Audio capture error: ")+count);
            for(int i=0;i<count;i++){if(samples>=8000)data[samples-8000]=buffer[i];samples++;}
            if(done()){
                AudioTimestamp stamp=new AudioTimestamp();
                if(record.getTimestamp(stamp,AudioTimestamp.TIMEBASE_BOOTTIME)!=AudioRecord.SUCCESS)throw new IllegalStateException(L.t("Android did not return the exact audio stream time"));
                anchor=SyncMath.captureAnchor(stamp.nanoTime,stamp.framePosition,samples,data.length);
                long age=SystemClock.elapsedRealtime()-(anchor+6000);
                if(age < -100||age>1000)throw new IllegalStateException(L.t("Audio stream time is unreliable"));
            }
        }
    }
    private static double rms(short[] data){double sum=0;for(short value:data)sum+=(double)value*value;return Math.sqrt(sum/data.length)/32768;}
    private static String message(Exception failure){String message=failure.getMessage();return message==null?failure.getClass().getSimpleName():message;}
    private void cleanup(){
        if(projection!=null){projection.unregisterCallback(revoked);projection.stop();projection=null;}
        if(volume!=null){volume.restore();volume=null;}
        if(wake!=null&&wake.isHeld())wake.release();
        if(ownsMeasurement){model.measuringAudio=false;ownsMeasurement=false;model.notifyChanged();}
    }
    @Override public void onDestroy(){cancelled.set(true);if(worker!=null)worker.interrupt();cleanup();super.onDestroy();}
    @Override public void onTaskRemoved(Intent root){cancelled.set(true);if(worker!=null)worker.interrupt();else stopSelf();}
    @Override public IBinder onBind(Intent intent){return null;}
}
