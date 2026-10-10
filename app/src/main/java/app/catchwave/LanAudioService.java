// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;
import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.hardware.*;
import android.media.*;
import android.os.*;

public final class LanAudioService extends Service implements SensorEventListener {
    static final String START="app.catchwave.LAN_START",STOP="app.catchwave.LAN_STOP",REFRESH="app.catchwave.LAN_REFRESH",CALIBRATE="app.catchwave.LAN_CALIBRATE";
    private static final String CHANNEL="catchwave_lan";private static final int NOTICE=24;
    private final Handler main=new Handler(Looper.getMainLooper());private final LanState state=LanState.INSTANCE;
    private final GestureEngine engine=new GestureEngine(this::gesture);
    private SensorManager sensors;private AudioManager manager;private LanSettings settings;
    private LanReceiver receiver;private PowerManager.WakeLock lock;private AudioFocusRequest focus;
    private boolean registered,destroyed;private int mutedVolume=-1;
    private long lastGyro=-1;private int volumeBefore=-1,volumeAfter=-1;private String lastVolumeAction="NONE";private long volumeAdjustments,volumeBoundarySkips;
    private VolumeStep.Device localVolume;
    private long lastOrientation=-1,orientationSamples;private boolean orientationRegistered;private int displayRotation=-1;
    private long accelerationSamples,gyroSamples,staleSamples,gestureActions,acceptedAcks,rejectedAcks;
    static volatile boolean uiVisible;
    static void maybeAutoStart(Context context){
        LanSettings settings=new LanSettings(context);
        if(settings.paired()&&settings.resume()&&!LanState.INSTANCE.started&&!SessionModel.INSTANCE.running&&!SessionModel.INSTANCE.measuringAudio){
            LanState state=LanState.INSTANCE;state.reset(settings);state.started=true;state.transition(LanConnection.Stage.STARTING);
            try{context.startForegroundService(new Intent(context,LanAudioService.class).setAction(START));}
            catch(RuntimeException failure){state.started=false;state.fail(settings,LanConnection.Error.ANDROID_START,LanConnection.Stage.STARTING);}
        }
    }
    @Override public void onCreate(){
        super.onCreate();settings=new LanSettings(this);sensors=getSystemService(SensorManager.class);manager=getSystemService(AudioManager.class);
        localVolume=new VolumeStep.Device(){
            public int current(){return manager.getStreamVolume(AudioManager.STREAM_MUSIC);}
            public int min(){return manager.getStreamMinVolume(AudioManager.STREAM_MUSIC);}
            public int max(){return manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);}
            public boolean fixed(){return manager.isVolumeFixed();}
            public void adjust(int direction){manager.adjustStreamVolume(AudioManager.STREAM_MUSIC,direction>0?AudioManager.ADJUST_RAISE:AudioManager.ADJUST_LOWER,0);}
        };
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"Звук с ПК",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        if(STOP.equals(intent.getAction())){settings.prefs.edit().putBoolean("resume",false).apply();stopSelf();return START_NOT_STICKY;}
        if(REFRESH.equals(intent.getAction())){if(receiver!=null&&!settings.hostControl())receiver.revokePending();refreshSensors();return START_NOT_STICKY;}
        if(CALIBRATE.equals(intent.getAction())){engine.reset(SystemClock.elapsedRealtime());return START_NOT_STICKY;}
        if(receiver!=null)return START_NOT_STICKY;
        if(!settings.paired()||!settings.resume()||SessionModel.INSTANCE.running||SessionModel.INSTANCE.measuringAudio){stopSelf();return START_NOT_STICKY;}
        try {
            startForeground(NOTICE,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK|ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            state.started=true;
            receiver=new LanReceiver(settings,new LanReceiver.Listener(){
                public boolean ready(){
                    java.util.concurrent.CompletableFuture<Boolean> result=LanFocus.request(main,
                        ()->!destroyed&&receiver!=null&&settings.resume()&&!SessionModel.INSTANCE.running&&!SessionModel.INSTANCE.measuringAudio,
                        ()->receiver!=null&&receiver.fresh(),()->{
                        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                            .setOnAudioFocusChangeListener(change->{if(change==AudioManager.AUDIOFOCUS_LOSS||change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT){state.fail(settings,LanConnection.Error.AUDIO_FOCUS_LOST,state.stage);stopSelf();}else if(receiver!=null)receiver.volume(change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK?.2f:1f);},main).build();
                        return manager.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                    });
                    try{return result.get(1500,java.util.concurrent.TimeUnit.MILLISECONDS);}catch(Exception failure){return false;}
                }
                public void stage(LanConnection.Stage value){main.post(()->{if(!destroyed&&state.error==LanConnection.Error.NONE){state.transition(value);getSystemService(NotificationManager.class).notify(NOTICE,notification());}});}
                public void failed(LanConnection.Error code,LanConnection.Stage at){main.post(()->{if(!destroyed){state.fail(settings,code,at);refreshSensors();}});}
                public void policy(boolean allowed){main.post(()->{if(!destroyed){state.hostAllowed=allowed;if(!allowed&&receiver!=null)receiver.revokePending();engine.tiltControl(effectiveControl());state.changed();}});}
                public byte[] report(){return new byte[]{1,0,(byte)state.sensorCode,(byte)(settings.hostControl()?1:0),(byte)(effectiveControl()?1:0)};}
                public void connected(){main.post(()->{if(destroyed||state.error!=LanConnection.Error.NONE)return;state.connected=true;refreshSensors();main.post(health);});}
                public void finished(){main.post(()->{if(!destroyed)stopSelf();});}
                public void ack(boolean accepted){main.post(()->{if(!destroyed){if(accepted)acceptedAcks++;else rejectedAcks++;state.update(accepted?"Команда передана Windows":"Команда отклонена","Windows подтвердил доставку; результат зависит от активного плеера.");}});}
            });
            new Thread(receiver::run,"CatchWave-LAN").start();
        }catch(RuntimeException failure){state.fail(settings,LanConnection.Error.ANDROID_START,LanConnection.Stage.STARTING);stopSelf();}
        return START_NOT_STICKY;
    }
    private final Runnable health=new Runnable(){public void run(){
        if(destroyed||receiver==null)return;
        engine.tiltControl(effectiveControl());
        if(!receiver.fresh()){state.fail(settings,LanConnection.Error.AUDIO_STALLED,state.stage);refreshSensors();stopSelf();return;}
        else {if(!state.connected){state.connected=true;refreshSensors();}
            if(settings.screenOff()){
                if(lock==null){lock=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"CatchWave:LAN");lock.setReferenceCounted(false);}
                lock.acquire(5000);
            }else if(lock!=null&&lock.isHeld())lock.release();
        }updateSensorStatus();state.statistics=receiver.statistics()+"\n"+engine.summary(SystemClock.elapsedRealtime());state.changed();main.postDelayed(this,500);
    }};
    private void refreshSensors(){
        engine.tiltControl(effectiveControl());
        boolean wanted=GestureGate.visibleSession(state.connected,settings.gestures(),uiVisible,settings.screenOff())&&!destroyed;
        if(wanted==registered)return;
        sensors.unregisterListener(this);registered=false;orientationRegistered=false;lastGyro=-1;lastOrientation=-1;engine.setEnabled(false,SystemClock.elapsedRealtime());
        if(wanted){
            Sensor accel=sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),gyro=sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            if(accel!=null&&gyro!=null){boolean a=sensors.registerListener(this,accel,10000,main),g=sensors.registerListener(this,gyro,10000,main);registered=a&&g;if(!registered)sensors.unregisterListener(this);}
            if(registered){
                Sensor vector=sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
                if(vector==null)vector=sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
                if(vector!=null)orientationRegistered=sensors.registerListener(this,vector,10000,main);
            }
            engine.setEnabled(registered,SystemClock.elapsedRealtime());
        }
        state.listening=registered;updateSensorStatus();state.changed();
    }
    private boolean effectiveControl(){LanReceiver r=receiver;return state.connected&&r!=null&&r.localControlAllowed();}
    private void updateSensorStatus(){
        long now=SystemClock.elapsedRealtime();
        state.sensorCode=!state.connected||!settings.gestures()||(!uiVisible&&!settings.screenOff())?0:!registered?3:!GestureGate.freshSample(now,lastGyro)?4:!orientationRegistered?5:!engine.neutralReady(now)?1:2;
        if(!state.connected)state.sensorDetail="Датчики выключены — нет свежего соединения";
        else if(!settings.gestures())state.sensorDetail="Жесты выключены пользователем";
        else if(!uiVisible&&!settings.screenOff())state.sensorDetail="Жесты приостановлены — CatchWave не виден";
        else if(!registered)state.sensorDetail="Не удалось включить акселерометр и гироскоп";
        else if(!GestureGate.freshSample(now,lastGyro))state.sensorDetail="Датчики включены — ожидание свежих измерений";
        else if(!orientationRegistered)state.sensorDetail="Датчики работают; датчик ориентации недоступен, наклоны выключены";
        else if(!engine.neutralReady(now))state.sensorDetail="Нейтраль не готова — держите телефон спокойно 1,5 секунды";
        else if(!engine.tiltAvailable(now))state.sensorDetail="Нейтраль готова; для наклонов держите верх телефона вверх";
        else state.sensorDetail="Датчики работают — нейтраль готова";
    }
    private void gesture(GestureEngine.Action action){
        if(!registered||receiver==null||!receiver.fresh()||!GestureGate.visibleSession(state.connected,settings.gestures(),uiVisible,settings.screenOff()))return;
        boolean volumeTilt=action==GestureEngine.Action.VOLUME_UP||action==GestureEngine.Action.VOLUME_DOWN;
        if(volumeTilt&&!effectiveControl()){engine.tiltControl(false);return;}
        gestureActions++;
        if(action==GestureEngine.Action.VOLUME_UP||action==GestureEngine.Action.VOLUME_DOWN||action==GestureEngine.Action.MUTE){volumeBefore=manager.getStreamVolume(AudioManager.STREAM_MUSIC);lastVolumeAction=action.name();}
        switch(action){
            case VOLUME_UP:case VOLUME_DOWN:
                if(!receiver.executeLocalControl(settings.controlEpoch(),receiver.policyGeneration(),()->adjustHeldVolume(action))){engine.tiltControl(false);return;}
                break;
            case MUTE:
                int current=manager.getStreamVolume(AudioManager.STREAM_MUSIC);
                if(mutedVolume>=0&&current==0){manager.setStreamVolume(AudioManager.STREAM_MUSIC,mutedVolume,0);mutedVolume=-1;}
                else {mutedVolume=current;manager.setStreamVolume(AudioManager.STREAM_MUSIC,0,0);}break;
            case PLAY_PAUSE:if(effectiveControl())receiver.send(1);return;case NEXT:if(effectiveControl())receiver.send(2);return;case PREVIOUS:if(effectiveControl())receiver.send(3);return;
        }
        volumeAfter=manager.getStreamVolume(AudioManager.STREAM_MUSIC);
        state.update("Громкость телефона: "+volumeBefore+" → "+volumeAfter,
            manager.isVolumeFixed()?"Android сообщает фиксированную громкость.":"Громкость регулируется локально на Android.");
    }
    private void adjustHeldVolume(GestureEngine.Action action){
        VolumeStep.Result step=VolumeStep.apply(localVolume,action==GestureEngine.Action.VOLUME_UP?1:-1);
        volumeBefore=step.before;volumeAfter=step.after;
        if(step.adjusted)volumeAdjustments++;else volumeBoundarySkips++;
        // A no-op at zero must preserve the checkpoint owned by six-tap mute.
        if(step.after!=step.before)mutedVolume=-1;
    }
    @Override public void onSensorChanged(SensorEvent event){
        engine.tiltControl(effectiveControl());
        if(!registered||receiver==null||!receiver.fresh()||!GestureGate.visibleSession(state.connected,settings.gestures(),uiVisible,settings.screenOff()))return;
        long now=SystemClock.elapsedRealtime(),sampleAt=event.timestamp/1000000;
        if(!GestureGate.freshSample(now,sampleAt)){staleSamples++;lastGyro=-1;engine.reset(now,"SENSOR_STALE");return;}
        int rotation=getSystemService(android.view.WindowManager.class).getDefaultDisplay().getRotation();
        if(rotation!=displayRotation){displayRotation=rotation;engine.displayRotation(rotation,now);lastGyro=-1;lastOrientation=-1;}
        float[] v=event.values;
        if(event.sensor.getType()==Sensor.TYPE_GYROSCOPE){gyroSamples++;lastGyro=sampleAt;engine.gyro(v[0],v[1],v[2]);}
        else if(event.sensor.getType()==Sensor.TYPE_GAME_ROTATION_VECTOR||event.sensor.getType()==Sensor.TYPE_ROTATION_VECTOR){
            orientationSamples++;
            if(!GestureGate.freshSample(now,lastGyro)){engine.reset(now,"GYRO_STALE");lastOrientation=-1;return;}
            float[] q=new float[4];SensorManager.getQuaternionFromVector(q,v);engine.orientation(sampleAt,q[0],q[1],q[2],q[3]);lastOrientation=sampleAt;
        }else {accelerationSamples++;if(GestureGate.freshSample(now,lastGyro)&&(!orientationRegistered||GestureGate.freshSample(now,lastOrientation)))engine.acceleration(sampleAt,v[0],v[1],v[2]);else engine.reset(now,"DEPENDENCY_STALE");}
    }
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy){}
    private Notification notification(){
        PendingIntent open=PendingIntent.getActivity(this,24,new Intent(this,LanAudioActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,25,new Intent(this,LanAudioService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_wave).setContentTitle("CatchWave — "+state.status).setContentText("Нажмите для состояния и управления")
            .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Остановить",stop).build()).build();
    }
    @Override public void onDestroy(){
        destroyed=true;main.removeCallbacksAndMessages(null);sensors.unregisterListener(this);engine.setEnabled(false,SystemClock.elapsedRealtime());
        if(receiver!=null)receiver.close();if(lock!=null&&lock.isHeld())lock.release();if(focus!=null)manager.abandonAudioFocusRequest(focus);
        if(mutedVolume>=0&&manager.getStreamVolume(AudioManager.STREAM_MUSIC)==0)manager.setStreamVolume(AudioManager.STREAM_MUSIC,mutedVolume,0);
        state.stopped(settings);
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
    String statistics(){return receiver==null?"Поток не запущен":receiver.statistics();}
    @Override public void onTaskRemoved(Intent intent){stopSelf();super.onTaskRemoved(intent);}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override protected void dump(java.io.FileDescriptor fd,java.io.PrintWriter out,String[] args){
        // Targeted ADB diagnostics: counters only, no pairing secrets, endpoints, sensor vectors or audio.
        out.println("CatchWave LAN "+BuildConfig.VERSION_NAME);
        out.println("stage="+state.stage+" error="+state.error+" lastStage="+state.lastStage+" effectiveControl="+effectiveControl()+" hostAllowed="+state.hostAllowed);
        out.println("started="+state.started+" connected="+state.connected+" sensorsRegistered="+registered+" uiVisible="+uiVisible);
        out.println("gestures="+settings.gestures()+" screenOff="+settings.screenOff()+" resume="+settings.resume()+" wakeHeld="+(lock!=null&&lock.isHeld()));
        out.println("accelerationSamples="+accelerationSamples+" gyroSamples="+gyroSamples+" staleSamples="+staleSamples+" gestureActions="+gestureActions);
        out.println("acceptedAcks="+acceptedAcks+" rejectedAcks="+rejectedAcks);
        out.println("orientationRegistered="+orientationRegistered+" orientationSamples="+orientationSamples+" hostControl="+settings.hostControl());
        engine.dump(out,SystemClock.elapsedRealtime());
        out.println("volumeAdjustments="+volumeAdjustments+" volumeBoundarySkips="+volumeBoundarySkips);
        out.println("musicVolume="+manager.getStreamVolume(AudioManager.STREAM_MUSIC)+" musicVolumeMax="+manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)+" fixedVolume="+manager.isVolumeFixed()+" volumeBefore="+volumeBefore+" volumeAfter="+volumeAfter+" lastVolumeAction="+lastVolumeAction);
        LanReceiver r=receiver;if(r!=null)r.dump(out);
    }
}
