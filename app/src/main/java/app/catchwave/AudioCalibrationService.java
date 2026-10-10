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

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null||STOP.equals(intent.getAction())){cancelled.set(true);if(worker!=null)worker.interrupt();else stopSelf();return START_NOT_STICKY;}
        if(worker!=null)return START_NOT_STICKY;
        Intent consent=Build.VERSION.SDK_INT>=33?intent.getParcelableExtra("consent",Intent.class):intent.getParcelableExtra("consent");
        controller=new MediaBridge(this).controller();target=new CalibrationTarget(controller,model.track);
        if(consent==null||intent.getIntExtra("result",0)!=Activity.RESULT_OK||!target.valid(controller,model)){
            model.record("Уточнение звука: нет разрешения или выбранный трек уже изменился");stopSelf();return START_NOT_STICKY;
        }
        try{
            NotificationManager nm=getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("audio_alignment","Уточнение по звуку",NotificationManager.IMPORTANCE_LOW));
            PendingIntent open=PendingIntent.getActivity(this,21,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent stop=PendingIntent.getService(this,22,new Intent(this,AudioCalibrationService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE);
            Notification notification=new Notification.Builder(this,"audio_alignment").setSmallIcon(R.drawable.ic_wave).setContentTitle("Сравниваю звук")
                .setContentText("Микрофон и внутренний звук YouTube Music · до 30 секунд").setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(null,"Отменить",stop).build()).build();
            startForeground(21,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE|ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            projection=getSystemService(MediaProjectionManager.class).getMediaProjection(Activity.RESULT_OK,consent);
            if(projection==null)throw new IllegalStateException("Android не предоставил захват звука");
            projection.registerCallback(revoked,main);
            volume=new CaptureVolume(getSystemService(AudioManager.class));
            if(!volume.mute())throw new IllegalStateException("Не удалось выключить динамик телефона");
            wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"CatchWave:audioCompare");wake.acquire(35000);
            ownsMeasurement=true;model.measuringAudio=true;model.audioLagMs=Double.NaN;model.audioVerified=false;
            model.update("Сравниваю звук · динамик выключен","Внешняя музыка должна играть непрерывно. Запись остаётся в памяти телефона.");
            deadline=SystemClock.elapsedRealtime()+30000;
            worker=new Thread(this::measure,"CatchWave-audio-compare");worker.start();
        }catch(Exception failure){model.update("Уточнение по звуку недоступно",message(failure));stopSelf();}
        return START_NOT_STICKY;
    }
    private void measure(){
        String result="Измерение отменено";
        try{
            int uid=getPackageManager().getApplicationInfo(MediaBridge.PACKAGE,0).uid;
            AudioCorrection correction=new AudioCorrection();
            boolean storedLag=false;
            model.record("Уточнить по звуку: старт захвата; seek_lag="+(model.seekLagMs==Long.MIN_VALUE?"—":Long.toString(model.seekLagMs)));
            Thread.sleep(350);
            for(int pass=0;pass<3;pass++){
                check();
                if(CalibrationTarget.duration(controller)-CalibrationTarget.position(controller)<9000)throw new IllegalStateException("До конца записи мало времени — повтори на следующей песне");
                Capture[] pair=capture(uid);
                check();
                AudioAlignment.Estimate estimate=AudioAlignment.compare(pair[0].data,pair[0].anchor,pair[1].data,pair[1].anchor);
                model.record(String.format(Locale.ROOT,"Аудиозамер %d: refRms=%.5f micRms=%.5f anchors=%d/%d lag=%.2f ms confidence=%.2f windows=%.2f ms valid=%s",pass+1,rms(pair[0].data),rms(pair[1].data),pair[0].anchor,pair[1].anchor,estimate.lagMs,estimate.confidence,estimate.windowDifferenceMs,estimate.valid));
                if(!estimate.valid){
                    if(rms(pair[0].data)<.001)throw new IllegalStateException("Внутренний звук недоступен или тихий. YouTube Music либо Android могут ограничивать захват при нулевой громкости.");
                    if(rms(pair[1].data)<.0008)throw new IllegalStateException("Микрофон почти не слышит внешнюю музыку. Поднеси телефон ближе.");
                    if(pass==2)throw new IllegalStateException("Два фрагмента не дали однозначного совпадения. Проверь версию песни и непрерывное воспроизведение.");
                    model.record("Уточнить по звуку: окно не однозначно, повторяю");
                    continue;
                }
                model.audioLagMs=estimate.lagMs;
                if(!storedLag){
                    int next=(int)Math.max(-1200,Math.min(1200,getSharedPreferences("settings",MODE_PRIVATE).getInt("audio_lag_ms",0)+Math.round(estimate.lagMs)));
                    getSharedPreferences("settings",MODE_PRIVATE).edit().putInt("audio_lag_ms",next).apply();
                    storedLag=true;
                    model.record("Сохранена акустическая поправка "+next+" мс для следующего подхвата");
                }
                if(Math.abs(estimate.lagMs)<=15){model.audioVerified=true;result=String.format(Locale.ROOT,"Внутренний звук совпал: %+.1f мс. Задержка самого динамика сюда не входит.",estimate.lagMs);break;}
                if(pass==2){result=String.format(Locale.ROOT,"После двух поправок осталось %+.1f мс. Точное совпадение не подтверждено; дальнейшую перемотку остановил.",estimate.lagMs);break;}
                long advance=correction.nextAdvance(estimate.lagMs),position=CalibrationTarget.position(controller),to=position+advance;
                if(position<0||to<0||to>=CalibrationTarget.duration(controller)-9000)throw new IllegalStateException("Поправка выходит за доступный фрагмент записи");
                check();
                long requested=SystemClock.elapsedRealtime();controller.getTransportControls().seekTo(to);
                model.record("Аудиопоправка: "+advance+" мс; seekTo="+to);
                model.audioLagMs=Double.NaN;
                boolean acknowledged=false;
                long settledSession=-1,sessionUpdateAt=-1;
                for(int attempt=0;attempt<20;attempt++){
                    Thread.sleep(100);check();PlaybackState p=controller.getPlaybackState();
                    long session=CalibrationTarget.position(controller);
                    if(p.getLastPositionUpdateTime()>=requested&&Math.abs(session-(to+SystemClock.elapsedRealtime()-requested))<=180){acknowledged=true;settledSession=session;sessionUpdateAt=p.getLastPositionUpdateTime();break;}
                }
                if(!acknowledged)throw new IllegalStateException("YouTube Music не подтвердил перемотку. Повторную поправку не применяю.");
                long tEst=model.track==null?-1:model.track.positionAt(SystemClock.elapsedRealtime(),0);
                long lag=SeekClock.lag(requested,sessionUpdateAt);
                model.record("t_est="+tEst+" t_session_after_seek="+settledSession+" Δ="+(tEst<0?"—":Long.toString(SeekClock.delta(tEst,settledSession)))+" seek_lag="+lag);
                Thread.sleep(650);
            }
        }catch(InterruptedException ignored){result="Измерение отменено. Громкость восстановлена, если ты её не менял.";}
        catch(Exception failure){result=message(failure);}
        finally{
            String detail=result;
            main.post(()->{
                boolean owned=ownsMeasurement;
                cleanup();
                if(owned){model.record("Уточнить по звуку: итог verified="+model.audioVerified+" lag="+(Double.isFinite(model.audioLagMs)?String.format(Locale.ROOT,"%.1f",model.audioLagMs):"—")+" · "+detail);
                    if(model.running&&model.guardingTrack)model.update(model.audioVerified?"Звук сопоставлен":"Уточнение завершено",detail);}
                stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
            });
        }
    }
    private void check()throws InterruptedException{
        if(cancelled.get()||Thread.currentThread().isInterrupted())throw new InterruptedException();
        if(SystemClock.elapsedRealtime()>deadline)throw new IllegalStateException("Лимит измерения 30 секунд исчерпан");
        if(!target.valid(controller,model))throw new IllegalStateException("Воспроизведение изменилось. Уточнение остановлено, чтобы не перематывать другую запись.");
        if(!volume.muted())throw new IllegalStateException("Громкость изменена вручную. Уточнение остановлено.");
    }
    private Capture[] capture(int uid)throws Exception{
        if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new SecurityException("Разрешение микрофона отключено");
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
                check();if(SystemClock.elapsedRealtime()>end)throw new IllegalStateException("Android не вернул оба аудиопотока вовремя");
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
            if(count<0)throw new IllegalStateException("Ошибка аудиозахвата: "+count);
            for(int i=0;i<count;i++){if(samples>=8000)data[samples-8000]=buffer[i];samples++;}
            if(done()){
                AudioTimestamp stamp=new AudioTimestamp();
                if(record.getTimestamp(stamp,AudioTimestamp.TIMEBASE_BOOTTIME)!=AudioRecord.SUCCESS)throw new IllegalStateException("Android не вернул точное время аудиопотока");
                anchor=SyncMath.captureAnchor(stamp.nanoTime,stamp.framePosition,samples,data.length);
                long age=SystemClock.elapsedRealtime()-(anchor+6000);
                if(age < -100||age>1000)throw new IllegalStateException("Время аудиопотока недостоверно");
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
