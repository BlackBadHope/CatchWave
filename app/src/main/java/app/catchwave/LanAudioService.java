// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;
import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.hardware.*;
import android.media.*;
import android.net.wifi.WifiManager;
import android.os.*;
import android.hardware.usb.*;

public final class LanAudioService extends Service implements SensorEventListener {
    static final String START="app.catchwave.LAN_START",STOP="app.catchwave.LAN_STOP",REFRESH="app.catchwave.LAN_REFRESH",CALIBRATE="app.catchwave.LAN_CALIBRATE",ACTIVATE="app.catchwave.LAN_ACTIVATE",DEACTIVATE="app.catchwave.LAN_DEACTIVATE",USB="app.catchwave.USB_START";
    private static final String CHANNEL="catchwave_lan";private static final int NOTICE=24;
    private final Handler main=new Handler(Looper.getMainLooper());private final LanState state=LanState.INSTANCE;
    private final GestureEngine engine=new GestureEngine(this::gesture);
    private final ActivationTone tones=new ActivationTone();private ActivationFeedback feedback=new ActivationFeedback(tones::play);
    private String readiness="NONE";
    private SensorManager sensors;private AudioManager manager;private LanSettings settings;
    private LanReceiver receiver;private PowerManager.WakeLock lock;private LanAudioFocus focus;
    private final TransportEpoch transport=new TransportEpoch();private UsbAudioProxy usb;private boolean wifiFallback;
    private final LanRecovery recovery=new LanRecovery();private LanConnection.Error recoveryError=LanConnection.Error.NETWORK;
    private LanConnection.Stage recoveryStage=LanConnection.Stage.TCP;
    private String route="Wi-Fi";private boolean detachRegistered;
    private final BroadcastReceiver detach=new BroadcastReceiver(){public void onReceive(Context context,Intent intent){
        if(UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(intent.getAction())&&usb!=null&&!usb.present())usbLost();
    }};
    private WifiManager.WifiLock wifiLock;private boolean wifiUnavailable;private int lastPowerState=-1;
    private volatile boolean registered,commandReady;private boolean destroyed;
    private final GestureArming arming=new GestureArming();private long armRevision=-1,seenEpoch=-1,seenPolicy=-1,lastAccel=-1;private double accelNorm,gyroNorm;private int mutedVolume=-1;
    private long lastGyro=-1;private int volumeBefore=-1,volumeAfter=-1;private String lastVolumeAction="NONE";private long volumeAdjustments,volumeBoundarySkips;
    private VolumeStep.Device localVolume;
    private long lastOrientation=-1,orientationSamples;private boolean orientationRegistered;private int displayRotation=-1;
    private long accelerationSamples,gyroSamples,staleSamples,invalidSamples,gestureActions,acceptedAcks,rejectedAcks;
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
        super.onCreate();DiagnosticStore.start(this);settings=new LanSettings(this);sensors=getSystemService(SensorManager.class);manager=getSystemService(AudioManager.class);
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
        if(STOP.equals(intent.getAction())){settings.prefs.edit().putBoolean("resume",false).apply();clearTransport();stopSelf();return START_NOT_STICKY;}
        if(USB.equals(intent.getAction())){beginUsb(intent);return START_NOT_STICKY;}
        if(REFRESH.equals(intent.getAction())){
            // A queued activity refresh must not revive a service after owner disconnect.
            if(!settings.resume()){clearTransport();stopSelf();return START_NOT_STICKY;}
            if(receiver!=null&&!settings.hostControl())receiver.revokePending();refreshSensors();return START_NOT_STICKY;
        }
        if(CALIBRATE.equals(intent.getAction())){arming.reset("MANUAL_NEUTRAL");syncArming(SystemClock.elapsedRealtime());return START_NOT_STICKY;}
        if(ACTIVATE.equals(intent.getAction())||DEACTIVATE.equals(intent.getAction())){
            if(!settings.resume()){clearTransport();stopSelf();return START_NOT_STICKY;}
            long now=SystemClock.elapsedRealtime();syncArming(now);
            if(DEACTIVATE.equals(intent.getAction()))arming.reset("MANUAL_OFF");
            else if(registered&&orientationRegistered&&effectiveControl()&&GestureGate.freshSample(now,lastGyro)&&GestureGate.freshSample(now,lastAccel)&&GestureGate.freshSample(now,lastOrientation)&&arming.activate(now)){
                DiagnosticStore.LOG.event(DiagnosticLog.Event.CONTROL,"EXPLICIT_ACTIVATION");
            }else state.update("Активация недоступна","Проверьте соединение, разрешения управления и свежие показания датчиков, затем нажмите «Активировать управление».");
            syncArming(now);state.changed();return START_NOT_STICKY;
        }
        if(receiver!=null||usb!=null)return START_NOT_STICKY;state.transport="Wi-Fi";
        DiagnosticStore.LOG.begin();DiagnosticStore.LOG.event(DiagnosticLog.Event.RECONNECT,"START_REQUESTED");
        if(!settings.paired()||!settings.resume()||SessionModel.INSTANCE.running||SessionModel.INSTANCE.measuringAudio){stopSelf();return START_NOT_STICKY;}
        try {
            startForeground(NOTICE,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK|ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            holdPlaybackPower();
            state.started=true;
            startReceiver(transport.next());
        }catch(RuntimeException failure){state.fail(settings,LanConnection.Error.ANDROID_START,LanConnection.Stage.STARTING);stopSelf();}
        return START_NOT_STICKY;
    }
    private boolean current(long generation){return !destroyed&&transport.current(generation,settings.resume());}
    private void startReceiver(long generation){
            LatestCallback<Boolean> policyUi=new LatestCallback<>(main,allowed->{if(current(generation)){state.hostAllowed=allowed;if(!allowed&&receiver!=null)receiver.revokePending();syncArming(SystemClock.elapsedRealtime());state.changed();}});
            LatestCallback<LanConnection.Stage> stageUi=new LatestCallback<>(main,value->{if(current(generation)&&state.error==LanConnection.Error.NONE){state.transition(receiver!=null&&receiver.focusPaused()?LanConnection.Stage.PHONE_AUDIO:value);getSystemService(NotificationManager.class).notify(NOTICE,notification());}});
            receiver=new LanReceiver(settings,new LanReceiver.Listener(){
                public boolean ready(){
                    java.util.concurrent.CompletableFuture<Boolean> result=LanFocus.request(main,
                        ()->current(generation)&&receiver!=null&&!SessionModel.INSTANCE.running&&!SessionModel.INSTANCE.measuringAudio,
                        ()->current(generation)&&receiver!=null&&receiver.fresh(),()->{
                        focus=new LanAudioFocus(main,manager,()->current(generation)&&receiver!=null,
                            ()->receiver!=null&&receiver.fresh(),change->{if(current(generation)&&receiver!=null){receiver.focus(change);syncArming(SystemClock.elapsedRealtime());state.changed();}});
                        return focus.start();
                    });
                    try{return result.get(1500,java.util.concurrent.TimeUnit.MILLISECONDS);}catch(Exception failure){return false;}
                }
                public void stage(LanConnection.Stage value){stageUi.accept(value);}
                public void failed(LanConnection.Error code,LanConnection.Stage at){main.post(()->{if(current(generation)){if(usb!=null&&wifiFallback&&(code==LanConnection.Error.NETWORK||code==LanConnection.Error.HOST_CLOSED||code==LanConnection.Error.HOST_TIMEOUT)){usbLost();return;}if(retryWifi(code,at))return;cancelRecovery();state.fail(settings,code,at);refreshSensors();}});}
                public void policy(boolean allowed){policyUi.accept(allowed);}
                public byte[] report(){return new byte[]{1,0,(byte)state.sensorCode,(byte)(current(generation)&&settings.hostControl()?1:0),(byte)(current(generation)&&commandReady&&arming.commands(SystemClock.elapsedRealtime())&&effectiveControl()?1:0)};}
                public void connected(){main.post(()->{if(!current(generation)||state.error!=LanConnection.Error.NONE)return;
                    if(usb!=null&&usb.pairing()!=null&&!usb.pairing().host.equals("127.0.0.1")){
                        settings.rememberUsbRoute(usb.pairing());wifiFallback=true;DiagnosticStore.LOG.event(DiagnosticLog.Event.RECONNECT,"USB_WIFI_ROUTE_READY");
                    }
                    cancelRecovery();state.connected=true;refreshSensors();main.removeCallbacks(health);main.post(health);});}
                public void finished(){main.post(()->finishReceiver(generation));}
                public void ack(boolean accepted){main.post(()->{if(current(generation)){if(accepted)acceptedAcks++;else rejectedAcks++;state.update(accepted?"Команда передана Windows":"Команда отклонена","Windows подтвердил доставку; результат зависит от активного плеера.");}});}
            },()->current(generation)&&arming.commands(SystemClock.elapsedRealtime()),arming::revision);
            new Thread(receiver::run,"CatchWave-LAN").start();
    }
    private void finishReceiver(long generation){
        // Failure clears resume before finished arrives. The active receiver still owes service teardown.
        // A replaced receiver must never stop the new USB/Wi-Fi session.
        if(destroyed||!transport.current(generation,true))return;
        if(settings.resume()&&usb!=null&&wifiFallback)usbLost();else stopSelf();
    }
    private void clearTransport(){
        cancelRecovery();
        transport.next();main.removeCallbacks(health);if(receiver!=null){receiver.close();receiver=null;}
        if(usb!=null){usb.close();usb=null;}wifiFallback=false;
        if(focus!=null){focus.close();focus=null;}
    }
    private void beginUsb(Intent intent){
        if(!settings.resume()||SessionModel.INSTANCE.running||SessionModel.INSTANCE.measuringAudio){stopSelf();return;}
        UsbAccessory accessory=Build.VERSION.SDK_INT>=33?intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY,UsbAccessory.class):intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
        UsbManager usbManager=getSystemService(UsbManager.class);
        if(!UsbAudioProxy.matches(accessory)||!usbManager.hasPermission(accessory)){state.fail(settings,LanConnection.Error.USB_SETUP,state.stage);stopSelf();return;}
        // Duplicate activity/permission delivery must not tear down an opening or live cable stream.
        if(usb!=null&&usb.present()&&"USB".equals(state.transport))return;
        clearTransport();settings=new LanSettings(this);state.reset(settings);state.started=true;state.transition(LanConnection.Stage.STARTING);route=state.transport="USB";
        startForeground(NOTICE,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK|ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);holdPlaybackPower();refreshSensors();
        if(!detachRegistered){registerReceiver(detach,new IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED),Context.RECEIVER_NOT_EXPORTED);detachRegistered=true;}
        UsbAudioProxy opening=new UsbAudioProxy(this,accessory);usb=opening;long generation=transport.next();
        Runnable deadline=()->{if(current(generation)&&receiver==null){opening.close();state.fail(settings,LanConnection.Error.USB_SETUP,state.stage);stopSelf();}};
        main.postDelayed(deadline,15000);
        new Thread(()->{try{opening.open();main.post(()->{main.removeCallbacks(deadline);if(!current(generation)||usb!=opening){opening.close();return;}
            LanSettings saved=new LanSettings(this);wifiFallback=TransportEpoch.sameHost(saved,opening.pairing());
            if(!wifiFallback)saved.hostControl(false);
            settings=new LanSettings(this,opening.localPairing());DiagnosticStore.LOG.event(DiagnosticLog.Event.RECONNECT,"USB_CONNECTED");startReceiver(generation);
        });}catch(Exception failure){opening.close();main.post(()->{main.removeCallbacks(deadline);if(current(generation)){state.fail(settings,LanConnection.Error.USB_SETUP,state.stage);stopSelf();}});}},"CatchWave-USB-open").start();
    }
    private void usbLost(){
        if(usb==null)return;boolean fallback=wifiFallback&&settings.resume()&&!destroyed;
        clearTransport();settings=new LanSettings(this);
        if(!fallback){if(settings.resume())state.fail(settings,LanConnection.Error.USB_DISCONNECTED,state.stage);stopSelf();return;}
        route=state.transport="Wi-Fi";DiagnosticStore.LOG.event(DiagnosticLog.Event.RECONNECT,"USB_TO_WIFI");state.reset(settings);state.started=true;state.transition(LanConnection.Stage.STARTING);refreshSensors();beginRecovery();startReceiver(transport.next());
    }
    private final Runnable recoveryDeadline=()->{if(!destroyed&&settings.resume()&&recovery.active()){
        DiagnosticStore.LOG.event(DiagnosticLog.Event.RECONNECT,"RECOVERY_EXPIRED");state.fail(settings,recoveryError,recoveryStage);clearTransport();stopSelf();
    }};
    private void beginRecovery(){recovery.begin(SystemClock.elapsedRealtime());main.removeCallbacks(recoveryDeadline);main.postDelayed(recoveryDeadline,LanRecovery.WINDOW_MS);}
    private void cancelRecovery(){recovery.stop();main.removeCallbacks(recoveryDeadline);}
    private boolean retryWifi(LanConnection.Error code,LanConnection.Stage at){
        if(usb!=null||destroyed||!settings.resume()||!LanRecovery.recoverable(code)||!recovery.active()&&!state.connected)return false;
        if(!recovery.active())beginRecovery();recoveryError=code;recoveryStage=at;
        long delay=recovery.retry(code,SystemClock.elapsedRealtime());if(delay<0)return false;
        long generation=transport.next();main.removeCallbacks(health);if(receiver!=null){receiver.close();receiver=null;}
        if(focus!=null){focus.close();focus=null;}
        state.reset(settings);state.started=true;state.transition(LanConnection.Stage.BUFFERING);state.detail="Восстанавливаем соединение с тем же ПК. «Отключиться» отменяет повтор.";
        DiagnosticStore.LOG.event(DiagnosticLog.Event.RECONNECT,"WIFI_RETRY",delay);refreshSensors();
        main.postDelayed(()->{if(current(generation)&&recovery.active())startReceiver(generation);},delay);return true;
    }
    private final Runnable health=new Runnable(){public void run(){
        if(destroyed||receiver==null||receiver.failurePending())return;
        syncArming(SystemClock.elapsedRealtime());
        if(!receiver.playbackAlive()){state.fail(settings,LanConnection.Error.AUDIO_STALLED,state.stage);refreshSensors();stopSelf();return;}
        holdPlaybackPower();
        if(!receiver.fresh())receiver.waitingForAudio();
        else if(!state.connected){state.connected=true;refreshSensors();}
        updatePowerState();updateSensorStatus();state.diagnostic=receiver.diagnostic(manager.getStreamVolume(AudioManager.STREAM_MUSIC));state.statistics=receiver.statistics()+"\n"+engine.summary(SystemClock.elapsedRealtime());state.changed();main.postDelayed(this,500);
    }};
    @SuppressWarnings("deprecation") private void holdPlaybackPower(){
        if(lock==null){lock=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"CatchWave:LAN-audio");lock.setReferenceCounted(false);}
        // This owner-started foreground session releases the lock on every teardown.
        // Gesture preferences and delayed UI health callbacks must not expire audio power.
        if(!lock.isHeld()){lock.acquire();DiagnosticStore.LOG.event(DiagnosticLog.Event.POWER,"CPU_HELD");}
        if(!wifiUnavailable)try{
            if(wifiLock==null){WifiManager wifi=(WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);if(wifi==null){wifiUnavailable=true;return;}
                wifiLock=wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"CatchWave:LAN-audio");wifiLock.setReferenceCounted(false);}
            if(!wifiLock.isHeld()){wifiLock.acquire();DiagnosticStore.LOG.event(DiagnosticLog.Event.POWER,"WIFI_HELD");}
        }catch(RuntimeException failure){wifiUnavailable=true;DiagnosticStore.LOG.event(DiagnosticLog.Event.POWER,"WIFI_LOCK_UNAVAILABLE");}
    }
    private void releasePlaybackPower(){
        try{if(wifiLock!=null&&wifiLock.isHeld())wifiLock.release();}catch(RuntimeException failure){DiagnosticStore.LOG.event(DiagnosticLog.Event.POWER,"WIFI_RELEASE_FAILED");}
        finally{if(lock!=null&&lock.isHeld())lock.release();}
        DiagnosticStore.LOG.event(DiagnosticLog.Event.POWER,"RELEASED");
    }
    private void updatePowerState(){
        PowerManager power=getSystemService(PowerManager.class);int bits=(power.isInteractive()?1:0)|(power.isDeviceIdleMode()?2:0)|(power.isPowerSaveMode()?4:0)|(lock!=null&&lock.isHeld()?8:0)|(wifiLock!=null&&wifiLock.isHeld()?16:0);
        if(bits!=lastPowerState){lastPowerState=bits;DiagnosticStore.LOG.event(DiagnosticLog.Event.POWER,"STATE",bits,Build.VERSION.SDK_INT);}
    }
    private void refreshSensors(){
        syncArming(SystemClock.elapsedRealtime());
        boolean wanted=GestureGate.visibleSession(state.connected,settings.gestures(),uiVisible,settings.screenOff())&&!destroyed;
        if(wanted==registered){syncArming(SystemClock.elapsedRealtime());return;}
        sensors.unregisterListener(this);registered=false;orientationRegistered=false;lastGyro=-1;lastOrientation=-1;lastAccel=-1;arming.reset("SENSOR_SESSION");engine.setEnabled(false,SystemClock.elapsedRealtime());
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
        state.listening=registered;syncArming(SystemClock.elapsedRealtime());updateSensorStatus();state.changed();
    }
    private boolean effectiveControl(){LanReceiver r=receiver;return state.connected&&r!=null&&r.localControlAllowed();}
    private void syncArming(long now){
        LanReceiver r=receiver;long epoch=settings.controlEpoch(),policy=r==null?-1:r.policyGeneration();
        if(epoch!=seenEpoch||policy!=seenPolicy){seenEpoch=epoch;seenPolicy=policy;DiagnosticStore.LOG.event(DiagnosticLog.Event.CONTROL,"PERMISSION_CHANGE",settings.hostControl()?1:0,r!=null&&r.hostPermitted()?1:0,epoch,policy);arming.reset("PERMISSION_CHANGE");}
        arming.permit(registered&&orientationRegistered&&effectiveControl()&&GestureGate.visibleSession(state.connected,settings.gestures(),uiVisible,settings.screenOff()),now);
        armTransition(now);engine.tiltControl(arming.commands(now));updateReadiness(now);
    }
    private void armTransition(long now){
        feedback.update(arming.active(now),settings.activationSound());
        if(armRevision==arming.revision())return;armRevision=arming.revision();
        if("DISPLAY_NEUTRAL".equals(arming.reason()))engine.clearPendingGestures(now);else engine.reset(now,"ARMING_"+arming.reason());
        if(receiver!=null)receiver.revokePending();
        DiagnosticStore.LOG.event(DiagnosticLog.Event.ARMING,arming.reason(),arming.active(now)?1:0);
        if("ARMED".equals(arming.reason()))try{Vibrator vibrator=getSystemService(Vibrator.class);if(vibrator!=null&&vibrator.hasVibrator())vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0,45,90,45},-1));}catch(RuntimeException ignored){}
    }
    private void updateReadiness(long now){
        state.armed=arming.active(now);state.activationReady=arming.activationReady();commandReady=arming.commands(now)&&engine.neutralReady(now);state.controlReady=commandReady;
        String next=!effectiveControl()?"NO_PERMISSION":!registered||!orientationRegistered?"NO_SENSORS":!arming.active(now)?"NOT_ARMED":!engine.neutralReady(now)?"NEUTRAL_WAIT":"READY";
        if(!next.equals(readiness)){readiness=next;DiagnosticStore.LOG.event(DiagnosticLog.Event.CONTROL,next,state.armed?1:0,engine.neutralRemaining(now));state.changed();}state.controlReadiness=next;
        String detail="READY".equals(next)?"Команды готовы":"NEUTRAL_WAIT".equals(next)?"Команды ждут нейтраль: ещё "+((engine.neutralRemaining(now)+999)/1000)+" с спокойствия":arming.explanation();
        state.arming=arming.label(now)+"\n"+detail+"\nКод: "+arming.reason()+" / "+next;
    }
    private void sensorBreak(long now,String reason){arming.reset(reason);armTransition(now);lastGyro=lastOrientation=lastAccel=-1;updateReadiness(now);}
    private void updateSensorStatus(){
        long now=SystemClock.elapsedRealtime();
        state.sensorCode=!state.connected||!settings.gestures()||(!uiVisible&&!settings.screenOff())?0:!registered?3:!GestureGate.freshSample(now,lastGyro)?4:!orientationRegistered?5:!engine.neutralReady(now)?1:2;
        if(!state.connected)state.sensorDetail="Датчики выключены — нет свежего соединения";
        else if(!settings.gestures())state.sensorDetail="Жесты выключены пользователем";
        else if(!uiVisible&&!settings.screenOff())state.sensorDetail="Жесты приостановлены — CatchWave не виден";
        else if(!registered)state.sensorDetail="Не удалось включить акселерометр и гироскоп";
        else if(!GestureGate.freshSample(now,lastGyro))state.sensorDetail="Датчики включены — ожидание свежих измерений";
        else if(!orientationRegistered)state.sensorDetail="Ориентация недоступна — активация и все команды заблокированы";
        else if(!engine.neutralReady(now))state.sensorDetail="Нейтраль не готова — держите телефон спокойно 1,5 секунды";
        else if(!engine.tiltAvailable(now))state.sensorDetail="Нейтраль готова; для наклонов держите верх телефона вверх";
        else state.sensorDetail="Датчики работают — нейтраль готова";
    }
    private void gesture(GestureEngine.Action action){
        if(!registered||receiver==null||!receiver.fresh()||!GestureGate.visibleSession(state.connected,settings.gestures(),uiVisible,settings.screenOff())){DiagnosticStore.LOG.event(DiagnosticLog.Event.GESTURE,"BLOCKED_SESSION",action.ordinal());return;}
        long now=SystemClock.elapsedRealtime();syncArming(now);if(!arming.commands(now)||!engine.neutralReady(now)){DiagnosticStore.LOG.event(DiagnosticLog.Event.GESTURE,"BLOCKED_ARMING",action.ordinal());return;}
        DiagnosticStore.LOG.event(DiagnosticLog.Event.GESTURE,action.name());
        boolean volumeTilt=action==GestureEngine.Action.VOLUME_UP||action==GestureEngine.Action.VOLUME_DOWN;
        if(volumeTilt&&!effectiveControl()){engine.tiltControl(false);return;}
        gestureActions++;
        if(action==GestureEngine.Action.VOLUME_UP||action==GestureEngine.Action.VOLUME_DOWN||action==GestureEngine.Action.MUTE){volumeBefore=manager.getStreamVolume(AudioManager.STREAM_MUSIC);lastVolumeAction=action.name();}
        switch(action){
            case VOLUME_UP:case VOLUME_DOWN:
                if(!receiver.executeLocalControl(settings.controlEpoch(),receiver.policyGeneration(),()->adjustHeldVolume(action))){engine.tiltControl(false);return;}
                break;
            case MUTE:
                if(!receiver.executeLocalControl(settings.controlEpoch(),receiver.policyGeneration(),()->{
                    if(!arming.commands(SystemClock.elapsedRealtime()))return;
                    int current=manager.getStreamVolume(AudioManager.STREAM_MUSIC);
                    if(mutedVolume>=0&&current==0){manager.setStreamVolume(AudioManager.STREAM_MUSIC,mutedVolume,0);mutedVolume=-1;}
                    else {mutedVolume=current;manager.setStreamVolume(AudioManager.STREAM_MUSIC,0,0);}
                }))return;break;
            case PLAY_PAUSE:if(effectiveControl())receiver.send(1);return;case NEXT:if(effectiveControl())receiver.send(2);return;case PREVIOUS:if(effectiveControl())receiver.send(3);return;
        }
        volumeAfter=manager.getStreamVolume(AudioManager.STREAM_MUSIC);
        DiagnosticStore.LOG.event(DiagnosticLog.Event.VOLUME,action.name(),volumeBefore,volumeAfter,mutedVolume,volumeBefore==volumeAfter?0:1);
        state.update("Громкость телефона: "+volumeBefore+" → "+volumeAfter,
            manager.isVolumeFixed()?"Android сообщает фиксированную громкость.":"Громкость регулируется локально на Android.");
    }
    private void adjustHeldVolume(GestureEngine.Action action){
        if(!arming.commands(SystemClock.elapsedRealtime()))return;
        VolumeStep.Result step=VolumeStep.apply(localVolume,action==GestureEngine.Action.VOLUME_UP?1:-1);
        volumeBefore=step.before;volumeAfter=step.after;
        if(step.adjusted)volumeAdjustments++;else volumeBoundarySkips++;
        // A no-op at zero must preserve the checkpoint owned by six-tap mute.
        if(step.after!=step.before)mutedVolume=-1;
    }
    @Override public void onSensorChanged(SensorEvent event){
        syncArming(SystemClock.elapsedRealtime());
        if(!registered||receiver==null||!receiver.fresh()||!GestureGate.visibleSession(state.connected,settings.gestures(),uiVisible,settings.screenOff()))return;
        long now=SystemClock.elapsedRealtime(),sampleAt=event.timestamp/1000000;
        if(!GestureGate.freshSample(now,sampleAt)){staleSamples++;sensorBreak(now,"SENSOR_STALE");return;}
        float[] v=event.values;
        if(v.length<3){invalidSamples++;sensorBreak(now,"INVALID_SENSOR");return;}
        for(float value:v)if(!Float.isFinite(value)){invalidSamples++;sensorBreak(now,"INVALID_SENSOR");return;}
        int rotation=getSystemService(android.view.WindowManager.class).getDefaultDisplay().getRotation();
        if(rotation!=displayRotation){
            displayRotation=rotation;engine.displayRotation(rotation,now);arming.displayChange();armTransition(now);
            // Display axes change; the physical device-frame sensor dependencies remain valid.
        }
        if(event.sensor.getType()==Sensor.TYPE_GYROSCOPE){gyroSamples++;lastGyro=sampleAt;gyroNorm=Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]);engine.gyro(v[0],v[1],v[2]);}
        else if(event.sensor.getType()==Sensor.TYPE_GAME_ROTATION_VECTOR||event.sensor.getType()==Sensor.TYPE_ROTATION_VECTOR){
            orientationSamples++;
            if(lastGyro<0||lastAccel<0)return; // First samples can arrive in any order after registration/reset.
            if(!GestureGate.freshSample(now,lastGyro)){sensorBreak(now,"GYRO_STALE");return;}
            float[] q=new float[4];SensorManager.getQuaternionFromVector(q,v);
            if(GestureGate.freshSample(now,lastAccel))arming.sample(sampleAt,q[0],q[1],q[2],q[3],gyroNorm,accelNorm);
            else arming.reset("ACCEL_STALE");
            armTransition(now);if(arming.active(now))engine.orientation(sampleAt,q[0],q[1],q[2],q[3]);lastOrientation=sampleAt;
        }else {accelerationSamples++;lastAccel=sampleAt;accelNorm=Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]);
            if(GestureGate.freshSample(now,lastGyro)&&GestureGate.freshSample(now,lastOrientation)){
                if(arming.commands(now))engine.acceleration(sampleAt,v[0],v[1],v[2]);
            }else if(lastGyro>=0&&lastOrientation>=0)sensorBreak(now,"DEPENDENCY_STALE");
        }
        updateReadiness(now);
    }
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy){if(accuracy==SensorManager.SENSOR_STATUS_UNRELIABLE)sensorBreak(SystemClock.elapsedRealtime(),"SENSOR_ACCURACY");}
    private Notification notification(){
        PendingIntent open=PendingIntent.getActivity(this,24,new Intent(this,LanAudioActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,25,new Intent(this,LanAudioService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_wave).setContentTitle("CatchWave — "+state.status).setContentText("Нажмите для состояния и управления")
            .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Остановить",stop).build()).build();
    }
    @Override public void onDestroy(){
        destroyed=true;DiagnosticStore.LOG.event(DiagnosticLog.Event.STOP,state.error==LanConnection.Error.NONE?"SERVICE_DESTROYED":state.error.name());arming.reset("SESSION_END");armTransition(SystemClock.elapsedRealtime());tones.close();main.removeCallbacksAndMessages(null);sensors.unregisterListener(this);engine.setEnabled(false,SystemClock.elapsedRealtime());
        clearTransport();if(detachRegistered){unregisterReceiver(detach);detachRegistered=false;}releasePlaybackPower();
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
        out.println("transport="+route+" usbOpen="+(usb!=null)+" wifiFallback="+wifiFallback);
        out.println("stage="+state.stage+" error="+state.error+" lastStage="+state.lastStage+" effectiveControl="+effectiveControl()+" hostAllowed="+state.hostAllowed);
        out.println("started="+state.started+" connected="+state.connected+" sensorsRegistered="+registered+" uiVisible="+uiVisible);
        out.println("gestures="+settings.gestures()+" screenOff="+settings.screenOff()+" resume="+settings.resume()+" wakeHeld="+(lock!=null&&lock.isHeld()));
        PowerManager power=getSystemService(PowerManager.class);
        out.println("powerCpuHeld="+(lock!=null&&lock.isHeld())+" wifiHeld="+(wifiLock!=null&&wifiLock.isHeld())+" wifiUnavailable="+wifiUnavailable+" interactive="+power.isInteractive()+" idle="+power.isDeviceIdleMode()+" powerSave="+power.isPowerSaveMode());
        out.println("accelerationSamples="+accelerationSamples+" gyroSamples="+gyroSamples+" staleSamples="+staleSamples+" invalidSamples="+invalidSamples+" gestureActions="+gestureActions);
        out.println("acceptedAcks="+acceptedAcks+" rejectedAcks="+rejectedAcks);
        out.println("orientationRegistered="+orientationRegistered+" orientationSamples="+orientationSamples+" hostControl="+settings.hostControl());
        out.println("armingActive="+arming.active(SystemClock.elapsedRealtime())+" armingMode=BUTTON armingReason="+arming.reason()+" diagnosticStorageFailures="+DiagnosticStore.failures());
        out.println("controlReadiness="+readiness+" commandReady="+commandReady+" manualActivationReady="+arming.activationReady());
        engine.dump(out,SystemClock.elapsedRealtime());
        out.println("volumeAdjustments="+volumeAdjustments+" volumeBoundarySkips="+volumeBoundarySkips);
        out.println("musicVolume="+manager.getStreamVolume(AudioManager.STREAM_MUSIC)+" musicVolumeMax="+manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)+" fixedVolume="+manager.isVolumeFixed()+" volumeBefore="+volumeBefore+" volumeAfter="+volumeAfter+" lastVolumeAction="+lastVolumeAction);
        LanReceiver r=receiver;if(r!=null)r.dump(out);
        if(java.util.Arrays.asList(args).contains("--journal")){out.println("CatchWave metadata journal");out.print(DiagnosticStore.LOG.text());}
    }
}
