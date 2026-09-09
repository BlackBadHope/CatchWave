package app.catchwave;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.media.session.*;
import android.os.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SyncService extends Service {
    public static final String START="app.catchwave.START",STOP="app.catchwave.STOP",ADJUST="app.catchwave.ADJUST";
    public static final String USER_PAUSE="app.catchwave.USER_PAUSE",USER_HOLD="app.catchwave.USER_HOLD",USER_RESUME="app.catchwave.USER_RESUME";
    public static final String REJECT="app.catchwave.REJECT";
    public static final String OPEN_STARTED="app.catchwave.OPEN_STARTED",OPEN_FAILED="app.catchwave.OPEN_FAILED";
    private static final String CHANNEL="catchwave_session";
    private static final int NOTIFICATION=20;
    private final SessionModel model=SessionModel.INSTANCE;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private final ExecutorService resolver=Executors.newSingleThreadExecutor();
    private final RecognitionClient client=new RecognitionClient();
    private volatile RequestScope requests=new RequestScope();
    private volatile Future<?> recognitionTask,resolverTask;
    private volatile boolean active,captureDone;
    private volatile AudioRecord recorder;
    private volatile int captureGeneration;
    private CapturePause capturePause;
    private long quietStarted;
    private int lastPlayerState=PlaybackState.STATE_NONE;
    private String playerError="";
    private final SourceTimeline sourceTimeline=new SourceTimeline();
    private volatile boolean shortCapturePreferred;
    private TrackEndGuard endGuard;
    private boolean waitingNextSource;
    private long endedSourcePosition;
    private boolean compatibleOpening;
    private boolean sourceResumeReady;
    private long sourceLostAt,pauseRequestedAt,captureStarted;
    private String catalogProblem="",prefetchKey="";
    private MediaBridge bridge;
    private PowerManager.WakeLock wakeLock;
    private long started,lastMatch,lastSeek,acquireStarted,nativeDeadline,lastLaunchRequest;
    private volatile long lastLoud;
    private int seekAttempts,matchCount;
    private long learnedSeekLag=-1,seekSentAt,seekCommanded,seekEstAtSend;
    private boolean awaitingSeekSettle;
    private boolean pausedByUs,initialSeek,resolveRequested,playRequested,resumeFresh,ownsQueue,keepPlayerOnExit;
    private Thread microphone;
    private AudioManager audioManager;
    // Output selection can change without any device being disconnected.
    private final Runnable routeCheck=new Runnable(){public void run(){
        if(!active||!model.live)return;
        if(!MediaBridge.headphones(SyncService.this)){pauseOwned();finish("Выход музыки изменился","Live Sync остановлен: выбери наушники. Для динамика доступен обычный подхват.");return;}
        main.postDelayed(this,1000);
    }};
    private final AudioDeviceCallback routeCallback=new AudioDeviceCallback(){
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            if(active&&model.live&&!MediaBridge.headphones(SyncService.this)) { pauseOwned();finish("Наушники отключены","Live Sync остановлен. Подключите наушники и запустите снова."); }
        }
    };
    @Override public void onCreate(){
        super.onCreate();bridge=new MediaBridge(this);
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,"Подхват музыки",NotificationManager.IMPORTANCE_LOW));
        audioManager=getSystemService(AudioManager.class);audioManager.registerAudioDeviceCallback(routeCallback,main);
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent==null)return START_NOT_STICKY;
        if(OPEN_STARTED.equals(intent.getAction())){if(active){compatibleOpening=true;acquireStarted=lastLaunchRequest=SystemClock.elapsedRealtime();claimQueue(bridge.controller());model.record("Открытие выбранной записи через экран YouTube Music");}else stopSelf();return START_NOT_STICKY;}
        if(OPEN_FAILED.equals(intent.getAction())){finish("Не удалось открыть YouTube Music","Проверь установку плеера и повтори подхват.");return START_NOT_STICKY;}
        if(REJECT.equals(intent.getAction())){pauseOwned();if(model.track!=null)client.invalidate(model.track);model.aligned=false;model.launchTicket++;finish("Это другая запись","Неверное соответствие удалено из кеша. Повтори на другом фрагменте или выбери нужную версию в YouTube Music.");return START_NOT_STICKY;}
        if(USER_PAUSE.equals(intent.getAction())||USER_HOLD.equals(intent.getAction())||USER_RESUME.equals(intent.getAction())){
            if(active)userControl(intent.getAction());else stopSelf();return START_NOT_STICKY;
        }
        if(STOP.equals(intent.getAction())) {keepPlayerOnExit=true;finish("Сеанс остановлен","Управление музыкой снова у тебя.");return START_NOT_STICKY;}
        if(ADJUST.equals(intent.getAction())) {seekAttempts=0;lastSeek=0;initialSeek=false;awaitingSeekSettle=false;return START_NOT_STICKY;}
        if(active)return START_NOT_STICKY;
        boolean live=intent.getBooleanExtra("live",false);
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED||!MediaBridge.allowed(this)) {
            model.update("Нужны разрешения","Разрешите микрофон и управление плеером в приложении.");stopSelf();return START_NOT_STICKY;
        }
        if(live&&!MediaBridge.headphones(this)){model.update("Подключи наушники","Live Sync должен слышать внешний источник, а не динамик телефона.");stopSelf();return START_NOT_STICKY;}
        active=true;captureDone=false;model.running=true;model.live=live;model.track=null;model.aligned=false;model.needsOpen=false;model.manualHold=false;model.errorMs=Long.MAX_VALUE;
        model.resetTrace();model.record("Сеанс начат; поправка "+getSharedPreferences("settings",MODE_PRIVATE).getInt("adjustment",0)+" мс");
        model.progress=0;started=lastLoud=SystemClock.elapsedRealtime();lastMatch=0;
        try {
            startForeground(NOTIFICATION,notification("Слушаю музыку рядом…"),ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            wakeLock=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"CatchWave:listen");wakeLock.acquire(31*60*1000L);
            freshCapture();main.post(tick);if(live)main.postDelayed(routeCheck,1000);
        }catch(Exception e){finish("Не удалось включить микрофон",e.getMessage()==null?"Откройте приложение и повторите.":e.getMessage());}
        return START_NOT_STICKY;
    }
    private Notification notification(String text){
        Intent open=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,SyncService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_wave).setContentTitle(model.live?"Подхват · Live Sync":"Подхват музыки")
            .setContentText(text).setContentIntent(pi).setOngoing(active).setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(null,"Остановить",stop).build()).build();
    }
    private void notifyStatus(String text){getSystemService(NotificationManager.class).notify(NOTIFICATION,notification(text));}
    private void cancelRequests(){
        requests.cancel();
        Future<?> pending=recognitionTask;if(pending!=null)pending.cancel(true);
        pending=resolverTask;if(pending!=null)pending.cancel(true);
    }
    private void freshCapture(){
        stopService(new Intent(this,AudioCalibrationService.class));model.audioLagMs=Double.NaN;model.audioVerified=false;
        closeEndGuard();waitingNextSource=false;
        startForeground(NOTIFICATION,notification("Слушаю источник…"),ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        captureGeneration++;captureDone=true;resumeFresh=true;
        cancelRequests();requests=new RequestScope();
        model.compatibleLaunchRequested=false;model.launchTicket++;compatibleOpening=false;
        if(capturePause!=null)capturePause.release();
        model.track=null;model.aligned=false;model.needsOpen=false;model.manualHold=false;model.errorMs=Long.MAX_VALUE;model.tEstMs=model.tSessionAfterSeekMs=model.seekLagMs=Long.MIN_VALUE;model.progress=0;
        captureStarted=lastLoud=quietStarted=SystemClock.elapsedRealtime();lastMatch=0;lastPlayerState=PlaybackState.STATE_NONE;playerError="";catalogProblem="";prefetchKey="";ownsQueue=false;keepPlayerOnExit=false;
        sourceLostAt=pauseRequestedAt=0;sourceResumeReady=false;pausedByUs=false;
        sourceTimeline.reset();shortCapturePreferred=false;learnedSeekLag=getSharedPreferences("settings",MODE_PRIVATE).getLong("seek_lag_ms",-1);awaitingSeekSettle=false;seekSentAt=seekCommanded=seekEstAtSend=0;model.needsAudioRefine=false;
        if(learnedSeekLag>=0)model.record("Первый seek: сохранённый seek_lag="+learnedSeekLag+" мс");
        capturePause=new CapturePause(bridge.controller());
        if(!model.live)claimQueue(bridge.controller());
        model.record("Свежий замер #"+captureGeneration+"; пауза OnePlus запрошена="+capturePause.requested());
        model.update("Подготовка микрофона",capturePause.requested()?"Ставлю YouTube Music на паузу, чтобы слышать источник.":"Готовлю новый замер источника…");
        final int generation=captureGeneration;
        final RequestScope scope=requests;
        main.post(new Runnable(){public void run(){
            if(!active||generation!=captureGeneration)return;
            if(capturePause.ready()&&(microphone==null||!microphone.isAlive())){
                model.record("Пауза подтверждена; запускаю микрофон");captureDone=false;
                model.update("Слушаю источник","Музыка телефона приостановлена. Первое распознавание — по 3 секундам звука.");
                microphone=new Thread(()->capture(generation,scope),"CatchWave-microphone");microphone.start();
            }else if(SystemClock.elapsedRealtime()-quietStarted>=2000){finish("Не удалось подготовить запись","Поставь музыку телефона на паузу и повтори подхват.");}
            else main.postDelayed(this,50);
        }});
    }
    private void capture(int generation,RequestScope scope){
        AudioRecord local=null;
        AtomicBoolean requesting=new AtomicBoolean();
        try {
            int min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(min<=0)throw new IllegalStateException("Запись 16 кГц не поддерживается устройством");
            if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("Доступ к микрофону отозван");
            local=openMicrophone(Math.max(min*4,16000));
            recorder=local;if(local.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("Микрофон недоступен");
            // Prefer the phone microphone even when output is a Bluetooth headset.
            for(AudioDeviceInfo device:audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) if(device.getType()==AudioDeviceInfo.TYPE_BUILTIN_MIC){local.setPreferredDevice(device);break;}
            local.startRecording();short[] ring=new short[96000],buffer=new short[1600];int index=0;long samples=0,lastSubmitted=0,lastSubmittedSamples=0,lastUi=0;
            while(active&&!captureDone&&!model.manualHold&&generation==captureGeneration){
                int n=local.read(buffer,0,buffer.length,AudioRecord.READ_BLOCKING);
                if(n<0)throw new IllegalStateException("Микрофон прерван ("+n+")");if(n==0)continue;
                long now=SystemClock.elapsedRealtime();double sum=0;
                for(int i=0;i<n;i++){ring[index]=buffer[i];index=(index+1)%ring.length;sum+=(double)buffer[i]*buffer[i];}
                samples+=n;double rms=Math.sqrt(sum/n)/32768;model.level=rms;
                if(rms>0.0025)lastLoud=now;
                if(now-lastUi>250){model.progress=(int)Math.min(100,samples*100/48000);model.notifyChanged();lastUi=now;}
                if(model.live&&model.track!=null&&now-lastLoud>1400)main.post(this::onSilence);
                int window=SyncMath.captureWindow(samples,lastSubmitted==0||shortCapturePreferred);
                long interval=SyncMath.recognitionInterval(model.track!=null);
                if(window>0 && (!shortCapturePreferred||samples-lastSubmittedSamples>=window) && now-lastSubmitted>=interval && rms>0.001 && requesting.compareAndSet(false,true)){
                    short[] sample=new short[window];for(int i=0;i<window;i++)sample[i]=ring[(index-window+ring.length+i)%ring.length];
                    long anchor=now-window/16;
                    AudioTimestamp timestamp=new AudioTimestamp();
                    int timestampResult=local.getTimestamp(timestamp,AudioTimestamp.TIMEBASE_BOOTTIME);
                    if(timestampResult==AudioRecord.SUCCESS) {
                        anchor=SyncMath.captureAnchor(timestamp.nanoTime,timestamp.framePosition,samples,window);
                    }
                    long endAge=now-anchor-window/16;
                    AudioDeviceInfo route=local.getRoutedDevice();
                    model.record("Микрофон: timestamp="+timestampResult+" frame="+timestamp.framePosition+" samples="+samples+" anchor="+anchor+" endAge="+endAge+" мс; route="+(route==null?"?":route.getType())+" rms="+Math.round(rms*10000));
                    if(timestampResult!=AudioRecord.SUCCESS||endAge < -100||endAge>1000){requesting.set(false);main.post(()->{if(generation==captureGeneration)finish("Нет точной отметки микрофона","Android не вернул достоверное время записи. Повтори подхват.");});break;}
                    final long sampleAnchor=anchor;lastSubmitted=now;lastSubmittedSamples=samples;
                    model.record("Распознавание: отправлен фрагмент "+window/16+" мс");
                    recognitionTask=network.submit(()->{try{recognize(sample,sampleAnchor,generation,scope);}finally{requesting.set(false);}});
                }
                if(model.track==null&&now-captureStarted>35000){main.post(()->{if(active&&generation==captureGeneration)finish(sourceTimeline.hasSamples()?"Позиция источника нестабильна":"Не получилось распознать",sourceTimeline.hasSamples()?"За 35 секунд не получены три согласованных замера. Проверь непрерывное воспроизведение и версию записи, затем повтори.":"Поднесите телефон ближе к музыке и попробуйте снова.");});break;}
            }
        }catch(Exception e){if(active&&!captureDone&&generation==captureGeneration)main.post(()->{if(generation==captureGeneration)finish("Запись остановлена",e.getMessage()==null?"Микрофон недоступен":e.getMessage());});}
        finally {if(local!=null){try{local.stop();}catch(Exception ignored){}local.release();}recorder=null;}
    }
    private void recognize(short[] sample,long anchor,int generation,RequestScope scope){
        try {
            if(!active||generation!=captureGeneration)return;
            long requestAt=SystemClock.elapsedRealtime();
            Track match=client.recognize(sample,anchor,scope);
            if(!active||generation!=captureGeneration)return;
            if(match==null||!match.hasPosition()&&sample.length<96000){shortCapturePreferred=false;model.record("Фрагмент без пригодного таймкода; обработка="+(SystemClock.elapsedRealtime()-requestAt)+" мс");main.post(()->{if(active&&generation==captureGeneration&&model.track==null)model.update("Слушаю чуть дольше","Уточняю запись и её позицию по 6 секундам звука.");});return;}
            shortCapturePreferred=match.hasPosition();
            model.record("Распознано "+match.title+" / "+match.artist+"; offset="+match.offsetMs+" мс, skew="+match.timeSkew+" обработка="+(SystemClock.elapsedRealtime()-requestAt)+" мс");
            if(model.track!=null&&model.track.key.equals(match.key)){match.youtubeUrl=model.track.youtubeUrl;match.playbackTitle=model.track.playbackTitle;match.playbackArtist=model.track.playbackArtist;}
            else match.youtubeUrl=client.cached(match);
            main.post(()->{if(generation==captureGeneration)accept(match);});
        }catch(Exception e){if(scope.isCancelled())return;main.post(()->{if(active&&generation==captureGeneration&&!model.manualHold){pauseOwned();finish("Распознавание недоступно",e.getMessage()==null?"Проверьте интернет и попробуйте снова.":e.getMessage());}});}
    }
    private void accept(Track match){
        if(!active||model.manualHold)return;
        long now=SystemClock.elapsedRealtime();
        if(lastLoud>0&&now-lastLoud>1400){if(model.live)onSilence();sourceTimeline.reset();model.update("Источник затих","Жду непрерывного воспроизведения для новых замеров.");return;}
        // A delayed match from before the source went quiet cannot authorize resume.
        if(model.live&&pausedByUs&&(match.anchorMs<sourceLostAt||now-lastLoud>1400)){
            model.record("Старый фрагмент после паузы источника пропущен");return;
        }
        if(!match.hasPosition()){model.track=match;finish("Запись распознана, таймкод ненадёжен","Фрагмент совпал с другой скоростью или версией. Повтори подхват на другом моменте; эту позицию не применяю.");return;}
        if(waitingNextSource&&model.track!=null&&model.track.key.equals(match.key)&&match.positionAt(now,0)>=endedSourcePosition-2000){model.record("Завершённая запись не разрешает следующий трек очереди");return;}
        SourceTimeline.Result confirmation=sourceTimeline.offer(match);
        model.record("Проверка источника: "+confirmation.state+" n="+confirmation.count+" spread="+confirmation.spreadMs+" мс span="+confirmation.spanMs+" мс rate="+confirmation.rate);
        if(confirmation.state==SourceTimeline.State.IGNORED)return;
        if(confirmation.state==SourceTimeline.State.RATE_MISMATCH){pauseOwned();finish("Скорость источника отличается","Несколько замеров показывают нарастающее расхождение времени. Проверь скорость 1× и версию записи; постоянная поправка это не исправит.");return;}
        if(confirmation.state!=SourceTimeline.State.CONFIRMED){
            if(model.live&&model.track!=null&&!model.track.key.equals(match.key)){
                pauseOwned();model.compatibleLaunchRequested=false;model.needsOpen=false;model.launchTicket++;
            }
            if(confirmation.count>=1&&match.hasPosition()&&microphone!=null&&microphone.isAlive())prefetchCatalog(match);
            model.update("Проверяю позицию · "+confirmation.count+"/3",match.title+" — сравниваю разные фрагменты и ход времени источника.");return;
        }
        match=confirmation.track;
        if(match.youtubeUrl==null||match.youtubeUrl.isEmpty())match.youtubeUrl=client.cached(match);
        if(!model.live)captureDone=true;
        boolean changed=resumeFresh||waitingNextSource||model.track==null||!model.track.key.equals(match.key);
        if(changed||waitingNextSource){closeEndGuard();waitingNextSource=false;}
        if(changed&&!model.manualHold&&model.live&&model.track!=null)pauseOwned();
        model.track=match;lastMatch=match.anchorMs+match.sampleDurationMs;matchCount++;
        if(model.manualHold){model.update("Ручное управление","Пауза или перемотка приостановили синхронизацию. Нажмите «Синхронизировать» для продолжения.");return;}
        resumeFresh=false;
        if(changed){
            acquireStarted=now;seekAttempts=0;lastSeek=0;initialSeek=false;awaitingSeekSettle=false;pausedByUs=false;playRequested=false;resolveRequested=false;nativeDeadline=0;model.aligned=false;model.errorMs=Long.MAX_VALUE;catalogProblem="";sourceLostAt=pauseRequestedAt=0;sourceResumeReady=false;
            boolean remotelyRequested=false;
            if(capturePause!=null)capturePause.release();
            lastLaunchRequest=SystemClock.elapsedRealtime();
            compatibleOpening=false;model.compatibleLaunchRequested=false;model.launchTicket++;
            boolean compatible=getSharedPreferences("settings",MODE_PRIVATE).getBoolean("compatibleLaunch",false);
            MediaController player=bridge.controller();
            try{remotelyRequested=MediaBridge.isTrack(player,match)&&!isPlayerError(player)||!(compatible&&isPlayerError(player))&&bridge.requestTrack(match);}catch(Exception ignored){}
            if(remotelyRequested){
                claimQueue(player);
                model.record("Команда запуска отправлена; жду подтверждения записи");
                nativeDeadline=acquireStarted+4000;model.needsOpen=false;
                model.update("Найдено: "+match.title,"YouTube Music открывает запись. Подхватываю текущий момент…");
            }else {
                model.needsOpen=false;resolveLink(match);
            }
            notifyStatus(match.artist+" — "+match.title);
        }else if(pausedByUs){
            sourceResumeReady=true;resumeOwnedIfReady(bridge.controller(),now);
        }else if(model.aligned){seekAttempts=0;}
        model.notifyChanged();
    }
    private void resumeOwnedIfReady(MediaController player,long now){
        PlaybackState state=player==null?null:player.getPlaybackState();
        if(!sourceResumeReady||now-lastLoud>1400||!MediaBridge.isTrack(player,model.track)||state==null||state.getState()!=PlaybackState.STATE_PAUSED)return;
        if(pauseRequestedAt>0&&state.getLastPositionUpdateTime()<pauseRequestedAt)return;
        if(!MediaBridge.supports(player,PlaybackState.ACTION_PLAY)){finish("Плеер не разрешает возобновление","Повтори подхват после запуска музыки.");return;}
        acquireStarted=lastLaunchRequest=now;playRequested=true;initialSeek=false;awaitingSeekSettle=false;lastSeek=0;seekAttempts=0;
        pausedByUs=false;sourceResumeReady=false;pauseRequestedAt=sourceLostAt=0;model.aligned=false;
        player.getTransportControls().play();model.record("Возобновление источника: новый срок ожидания плеера");
        model.update("Возобновляю музыку","Жду подтверждения воспроизведения, затем уточню позицию.");
    }
    private void resolveLink(Track match){
        if(resolveRequested)return;resolveRequested=true;
        final int generation=captureGeneration;
        final RequestScope scope=requests;
        model.record("Резерв: поиск прямой ссылки YouTube Music");
        model.update("Найдено: "+match.title,"Ищу прямую ссылку для открытия плеера…");
        resolverTask=resolver.submit(()->{
            final RecognitionClient.Resolution result=client.resolveResult(match,scope);
            final String resolved=result.url;
            main.post(()->{
                if(!active||generation!=captureGeneration||model.manualHold||model.track==null||!model.track.key.equals(match.key))return;
                if(sourceTimeline.hasSamples()&&!sourceTimeline.isTracking(match.key))return;
                if(result.kind==RecognitionClient.Kind.CANCELLED)return;
                // A slow lookup must not restart a track already opened by the native player.
                if(MediaBridge.isTrack(bridge.controller(),model.track)&&!isPlayerError(bridge.controller())||initialSeek||model.aligned)return;
                if(result.kind!=RecognitionClient.Kind.FOUND){
                    catalogProblem=result.message;model.record("Поиск: "+result.kind+" · "+catalogProblem);
                    finish(result.kind==RecognitionClient.Kind.NO_MATCH?"Подходящая запись не найдена":"Поиск записи недоступен",catalogProblem+" Нажми «Подхватить», чтобы сделать новый замер.");return;
                }
                model.track.youtubeUrl=resolved;
                model.track.playbackTitle=match.playbackTitle;model.track.playbackArtist=match.playbackArtist;
                model.record("Каталог: "+match.playbackTitle+" / "+match.playbackArtist+"; ссылка="+resolved);
                if(!resolved.isEmpty()&&getSharedPreferences("settings",MODE_PRIVATE).getBoolean("compatibleLaunch",false)){
                    compatibleOpening=true;ownsQueue=true;model.needsOpen=true;model.compatibleLaunchRequested=true;
                    model.update("Запускаю найденную запись","YouTube Music кратко откроется. После установки таймкода вернёмся в «Подхват».");
                    notifyStatus("Открой «Подхват», чтобы запустить найденную запись");return;
                }
                boolean remote=false;if(!resolved.isEmpty())try{lastLaunchRequest=SystemClock.elapsedRealtime();remote=bridge.requestTrack(model.track);if(remote)claimQueue(bridge.controller());}catch(Exception ignored){}
                model.needsOpen=!remote;
                model.record(resolved.isEmpty()?"В каталоге не найдена та же запись":"Прямая ссылка найдена; команда отправлена="+remote);
                model.update("Найдено: "+model.track.title,remote?"Плеер запускает запись в фоне…":"Плееру нужно ручное открытие. Нажмите «Открыть YouTube Music», затем вернитесь сюда.");
                notifyStatus(model.needsOpen&&!MainActivity.visible?"Нажмите, чтобы открыть найденную песню.":model.track.title);
            });
        });
    }
    private void onSilence(){
        if(active&&!model.manualHold&&!resumeFresh&&model.live&&SystemClock.elapsedRealtime()-lastLoud>1400&&!pausedByUs&&model.aligned){
            sourceLostAt=SystemClock.elapsedRealtime();sourceResumeReady=false;
            sourceTimeline.reset();
            pauseOwned();model.update("Источник затих","Плеер поставлен на паузу. Жду нового уверенного совпадения.");notifyStatus("Источник затих · ожидание");
        }
    }
    private void pauseOwned(){
        MediaController c=bridge.controller();
        if(MediaBridge.isTrack(c,model.track)&&MediaBridge.supports(c,PlaybackState.ACTION_PAUSE)){
            PlaybackState s=c.getPlaybackState();if(s!=null&&s.getState()==PlaybackState.STATE_PLAYING){pauseRequestedAt=SystemClock.elapsedRealtime();c.getTransportControls().pause();pausedByUs=true;model.aligned=false;}
        }
    }
    private final Runnable tick=new Runnable(){public void run(){
        if(!active)return;
        try{syncTick();}catch(SecurityException e){finish("Доступ к плееру отключён","Включите доступ к уведомлениям для «Подхвата».");}
        if(active)main.postDelayed(this,200);
    }};
    private void syncTick(){
        long now=SystemClock.elapsedRealtime();
        if(now-started>30*60*1000){pauseOwned();finish("Сеанс завершён","Лимит одного сеанса Live Sync — 30 минут. Можно запустить снова.");return;}
        if(model.manualHold)return;
        restrainQueue();
        if(resumeFresh)return;
        Track t=model.track;
        if(t==null)return;
        if(endGuard!=null){
            TrackEndGuard.State boundary=endGuard.check(now);
            if(boundary==TrackEndGuard.State.FAILED){model.aligned=false;finish("Не удалось подтвердить паузу в конце трека","Проверь состояние YouTube Music. Приложение не подтвердило остановку очереди.");return;}
            if(boundary==TrackEndGuard.State.STOPPED){
                closeEndGuard();model.aligned=false;model.record("Конец записи: пауза YouTube Music подтверждена");
                if(!model.live){finish("Трек завершён — музыка на паузе","Следующий трек очереди не запускаю. Для новой песни нажми «Подхватить».");return;}
                waitingNextSource=true;endedSourcePosition=t.positionAt(now,0);sourceTimeline.reset();
                sourceLostAt=now;sourceResumeReady=false;pausedByUs=true;pauseRequestedAt=0;
                model.update("Жду следующий трек источника","Очередь YouTube Music приостановлена. Подтверждаю следующую песню микрофоном.");return;
            }
            if(boundary==TrackEndGuard.State.PAUSING){model.update("Останавливаю очередь плеера","Жду подтверждения паузы YouTube Music.");return;}
            if(model.aligned&&!model.live)return;
        }
        if(waitingNextSource)return;
        if(model.live&&now-lastMatch>16000&&!pausedByUs&&model.aligned){sourceLostAt=now;sourceResumeReady=false;sourceTimeline.reset();pauseOwned();model.update("Источник потерян","Музыка на паузе. Продолжаю искать подтверждённое совпадение.");}
        if(model.live&&sourceTimeline.hasSamples()&&!sourceTimeline.isTracking(t.key)){if(!pausedByUs)pauseOwned();return;}
        MediaController c=bridge.controller();
        PlaybackState p=c==null?null:c.getPlaybackState();
        if(p!=null&&p.getState()!=lastPlayerState){lastPlayerState=p.getState();MediaMetadata m=c.getMetadata();model.record("YTM state="+p.getState()+" pos="+p.getPosition()+" updated="+p.getLastPositionUpdateTime()+" speed="+p.getPlaybackSpeed()+" metadata="+(m==null?"?":m.getDescription()));}
        if(p!=null&&p.getState()==PlaybackState.STATE_ERROR&&p.getLastPositionUpdateTime()>=lastLaunchRequest){
            playerError=String.valueOf(p.getErrorMessage());model.aligned=false;
            if(!resolveRequested&&!compatibleOpening){model.record("Ошибка YouTube Music: "+playerError);resolveLink(t);}
            model.update("YouTube Music не запустил запись",playerError+". Пробую прямую ссылку; при неудаче понадобится ручной выбор.");timeoutAcquire(now);return;
        }
        if(c==null){if(!compatibleOpening&&(nativeDeadline==0||now>=nativeDeadline))resolveLink(t);model.update("Жду YouTube Music","Откройте найденную песню. Доступ к уведомлениям должен быть включён.");timeoutAcquire(now);return;}
        if(!MediaBridge.isTrack(c,t)){
            if(!compatibleOpening&&nativeDeadline>0&&now>=nativeDeadline)resolveLink(t);
            model.aligned=false;model.update("Плеер ещё не открыл найденную запись",t.title+" / "+t.artist+". Если распознана другая песня, повтори подхват на другом фрагменте.");timeoutAcquire(now);return;
        }
        if(!model.live)armQueueGuard(c,t);
        if(!t.hasPosition()){finish("Песня найдена, точная позиция недоступна","Сервис не вернул пригодный таймкод или источник меняет скорость записи.");return;}
        if(!MediaBridge.supports(c,PlaybackState.ACTION_SEEK_TO)){finish("Плеер не разрешает перемотку","YouTube Music не объявил поддержку перемотки для этой записи. Проверьте тип контента и ограничения аккаунта.");return;}
        if(p==null){timeoutAcquire(now);return;}
        if(pausedByUs){
            if(p.getState()==PlaybackState.STATE_PAUSED&&(pauseRequestedAt==0||p.getLastPositionUpdateTime()>=pauseRequestedAt)){pauseRequestedAt=0;resumeOwnedIfReady(c,now);}
            else if(pauseRequestedAt>0&&now-pauseRequestedAt>2000)finish("Плеер не подтвердил паузу","Останови музыку телефона и повтори подхват.");
            return;
        }
        if(p.getState()!=PlaybackState.STATE_PLAYING){
            if(!compatibleOpening&&p.getState()==PlaybackState.STATE_ERROR&&now>=nativeDeadline)resolveLink(t);
            if(!initialSeek&&!playRequested&&p.getState()==PlaybackState.STATE_PAUSED&&MediaBridge.supports(c,PlaybackState.ACTION_PLAY)){
                playRequested=true;c.getTransportControls().play();model.record("Запрошено воспроизведение совпавшей записи");
            }
            timeoutAcquire(now);return;
        }
        if(!Float.isFinite(p.getPlaybackSpeed())||Math.abs(p.getPlaybackSpeed()-1f)>0.005f){finish("Плеер меняет скорость","Для синхронизации нужна обычная скорость воспроизведения 1×.");return;}
        long tEst=estimatePosition(t,now);
        MediaMetadata meta=c.getMetadata();long duration=meta==null?0:meta.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if(duration>0&&tEst>=duration-500){finish("Фрагмент за пределами записи","Версии песни могут отличаться. Попробуйте снова на следующем фрагменте.");return;}
        long tSession=SyncMath.playerPosition(p.getPosition(),p.getLastPositionUpdateTime(),p.getPlaybackSpeed(),now,true);
        if(initialSeek&&tSession<0){timeoutAcquire(now);return;}
        long delta=tSession<0?Long.MAX_VALUE:SeekClock.delta(tEst,tSession);model.errorMs=delta;
        if(awaitingSeekSettle){
            if(!SeekClock.settled(now,seekSentAt,p.getLastPositionUpdateTime(),tSession,seekCommanded)){
                if(now-seekSentAt>5000)finish("Не удалось подтвердить синхронизацию","MediaSession не подтвердил перемотку. Запусти повторный подхват или проверь версию записи.");
                return;
            }
            long lag=SeekClock.lag(seekSentAt,p.getLastPositionUpdateTime());
            rememberSeekLag(lag);
            awaitingSeekSettle=false;
            model.tEstMs=tEst;model.tSessionAfterSeekMs=tSession;model.seekLagMs=lag;
            model.record("t_est="+tEst+" t_est_at_send="+seekEstAtSend+" t_session_after_seek="+tSession+" Δ="+delta+" seek_lag="+lag+" learned_lag="+learnedSeekLag);
            model.record("Уточнить по звуку: "+(model.live?"в Live не запускается":"доступно после подхвата, если Android даст захват"));
            if(Math.abs(delta)<=SyncMath.TOLERANCE_MS){confirmAligned(c,t);return;}
            if(seekAttempts<3){issueSeek(c,tEst,tSession,now,duration);return;}
            finish("Не удалось подтвердить синхронизацию","После компенсации seek_lag плеер всё ещё расходится с оценкой. Это не измерение динамика; при обычном подхвате нажми «Уточнить по звуку».");return;
        }
        if(!initialSeek||SyncMath.shouldSeek(delta,now-lastSeek,seekAttempts)){
            issueSeek(c,tEst,tSession,now,duration);return;
        }
        if(now-lastSeek<SeekClock.SETTLE_MS)return;
        if(Math.abs(delta)<=SyncMath.TOLERANCE_MS&&p.getLastPositionUpdateTime()>=lastSeek)confirmAligned(c,t);
        else if(now-lastSeek>5000&&(seekAttempts>=3||Math.abs(delta)<=SyncMath.TOLERANCE_MS))finish("Не удалось подтвердить синхронизацию","Плеер не подтвердил нужный таймкод. Запустите повторный подхват или проверьте версию записи.");
        model.notifyChanged();
    }
    private void issueSeek(MediaController c,long tEst,long tSession,long now,long duration){
        long commanded=SeekClock.command(tEst,learnedSeekLag);
        if(duration>0&&commanded>=duration-500)commanded=tEst;
        c.getTransportControls().seekTo(commanded);
        initialSeek=true;model.aligned=false;lastSeek=now;seekAttempts++;
        seekSentAt=now;seekCommanded=commanded;seekEstAtSend=tEst;awaitingSeekSettle=true;
        model.tEstMs=tEst;model.tSessionAfterSeekMs=tSession;
        PlaybackState state=c.getPlaybackState();
        model.record("Перемотка #"+seekAttempts+": t_est="+tEst+" commanded="+commanded+" seek_lag_comp="+(learnedSeekLag<0?0:learnedSeekLag)+" t_session="+tSession+" updated="+(state==null?"?":state.getLastPositionUpdateTime()));
        model.update("Выравниваю позицию","Жду settle MediaSession. 3×≤120 мс — согласованность распознавателя, не слышимый sync.");
    }
    private void confirmAligned(MediaController c,Track t){
        model.aligned=true;
        claimQueue(c);armQueueGuard(c,t);
        model.update(model.live?"Live Sync активен":"Таймкод установлен",model.live?"Слежу за согласованностью замеров и скоростью источника.":"Позиция проверена несколькими замерами (3×≤120 мс — согласованность распознавателя, не слышимый sync). YouTube Music подтвердил таймкод.");
        if(!model.live){
            cancelRequests();model.level=0;model.guardingTrack=true;model.needsAudioRefine=true;
            startForeground(NOTIFICATION,notification("В конце этой записи поставлю музыку на паузу"),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            model.update("Таймкод установлен · один трек","Микрофон выключен. Сверяю внутренний звук YouTube Music с микрофоном, если Android даст захват. Очередь в конце записи остановлю.");
        }
        model.notifyChanged();
    }
    private long estimatePosition(Track t,long now){
        android.content.SharedPreferences prefs=getSharedPreferences("settings",MODE_PRIVATE);
        return t.positionAt(now,prefs.getInt("adjustment",0)+prefs.getInt("audio_lag_ms",0));
    }
    private void rememberSeekLag(long lag){
        learnedSeekLag=SeekClock.blend(learnedSeekLag,lag);
        if(learnedSeekLag>=0)getSharedPreferences("settings",MODE_PRIVATE).edit().putLong("seek_lag_ms",learnedSeekLag).apply();
    }
    private void claimQueue(MediaController c){
        ownsQueue=true;MediaBridge.holdQueue(c);
    }
    private void restrainQueue(){
        MediaController c=bridge.controller();
        Track t=model.track;
        if(!model.live){
            claimQueue(c);
            if(t==null||!MediaBridge.isTrack(c,t)){
                if(MediaBridge.pausePlayer(c))model.record(t==null?"Очередь остановлена на время подхвата":"Очередь: чужая запись поставлена на паузу");
            }
            return;
        }
        if(t==null&&MediaBridge.pausePlayer(c))model.record("Очередь остановлена на время подхвата");
    }
    private void armQueueGuard(MediaController c,Track t){
        if(endGuard!=null||c==null||t==null)return;
        endGuard=new TrackEndGuard(c,t,main,()->{if(active)try{syncTick();}catch(SecurityException e){finish("Доступ к плееру отключён","Включи доступ к уведомлениям для «Подхвата».");}});
        model.record("Контроль очереди YouTube Music включён");
    }
    private void haltOwnedQueue(){
        if(!ownsQueue||model.live||keepPlayerOnExit)return;
        MediaBridge.holdQueue(bridge.controller());
        if(MediaBridge.pausePlayer(bridge.controller()))model.record("Очередь YouTube Music остановлена");
        ownsQueue=false;
    }
    private static boolean isPlayerError(MediaController c){PlaybackState p=c==null?null:c.getPlaybackState();return p!=null&&p.getState()==PlaybackState.STATE_ERROR;}
    private void timeoutAcquire(long now){if(now-acquireStarted>20000)finish("Не удалось открыть совпавшую запись",playerError.isEmpty()?"Распознаватель и YouTube Music могут выбрать разные версии. Проверь найденную песню, открой нужную запись и повтори подхват.":"YouTube Music: "+playerError+" Открой запись вручную и повтори подхват.");}
    private void userControl(String action){
        closeEndGuard();waitingNextSource=false;
        if(USER_RESUME.equals(action)){
            freshCapture();
        }else {
            if(capturePause!=null)capturePause.release();
            model.manualHold=true;model.aligned=false;
            model.compatibleLaunchRequested=false;model.needsOpen=false;model.launchTicket++;compatibleOpening=false;
            captureGeneration++;captureDone=true;cancelRequests();
            MediaController c=bridge.controller();
            if(USER_PAUSE.equals(action)&&MediaBridge.supports(c,PlaybackState.ACTION_PAUSE))c.getTransportControls().pause();
            model.update("Ручное управление","Прослушивание и поиск приостановлены. Нажмите «Синхронизировать» для нового замера.");
        }
        model.record("Управление: "+action.substring(action.lastIndexOf('.')+1));
    }
    private void finish(String title,String detail){
        stopService(new Intent(this,AudioCalibrationService.class));
        closeEndGuard();
        cancelRequests();
        if(keepPlayerOnExit){
            if(capturePause!=null&&capturePause.restore(bridge.controller()))model.record("Возвращено прежнее воспроизведение");
        }else {
            if(capturePause!=null)capturePause.release();
            haltOwnedQueue();
        }
        model.record(title+"; ошибка таймкода="+model.errorMs+" мс");
        active=false;captureDone=true;model.running=false;model.manualHold=false;model.needsAudioRefine=false;model.level=0;model.needsOpen=false;model.update(title,detail);
        model.compatibleLaunchRequested=false;
        main.removeCallbacks(tick);main.removeCallbacks(routeCheck);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
    }
    @Override public void onDestroy(){
        stopService(new Intent(this,AudioCalibrationService.class));
        closeEndGuard();
        cancelRequests();
        if(keepPlayerOnExit){
            if(capturePause!=null)capturePause.restore(bridge.controller());
        }else {
            if(capturePause!=null)capturePause.release();
            if(!model.live)MediaBridge.pausePlayer(bridge.controller());
        }
        active=false;captureDone=true;model.running=false;main.removeCallbacksAndMessages(null);
        if(microphone!=null)microphone.interrupt();network.shutdownNow();resolver.shutdownNow();
        if(audioManager!=null)audioManager.unregisterAudioDeviceCallback(routeCallback);
        if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();
        model.notifyChanged();super.onDestroy();
    }
    @Override public void onTaskRemoved(Intent rootIntent){finish("Сеанс остановлен","Приложение закрыто.");}
    private void prefetchCatalog(Track match){
        if(match==null||match.key.isEmpty()||match.key.equals(prefetchKey)||!client.cached(match).isEmpty())return;
        prefetchKey=match.key;
        final RequestScope scope=requests;
        final int generation=captureGeneration;
        resolver.submit(()->{
            RecognitionClient.Resolution result=client.resolveResult(match,scope);
            if(!active||generation!=captureGeneration)return;
            model.record("Каталог заранее: "+result.kind+(result.url==null||result.url.isEmpty()?"":" "+result.url));
        });
    }
    private AudioRecord openMicrophone(int buffer){
        AudioFormat format=new AudioFormat.Builder().setSampleRate(16000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
        try{
            AudioRecord.Builder builder=new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION).setAudioFormat(format).setBufferSizeInBytes(buffer);
            if(Build.VERSION.SDK_INT>=31)builder.setPrivacySensitive(true);
            AudioRecord record=builder.build();
            if(record.getState()==AudioRecord.STATE_INITIALIZED)return record;
            record.release();
        }catch(RuntimeException ignored){}
        return new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,buffer);
    }
    private void closeEndGuard(){model.guardingTrack=false;if(endGuard!=null){endGuard.close();endGuard=null;}}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override protected void dump(java.io.FileDescriptor fd,java.io.PrintWriter writer,String[] args){writer.println(model.report());}
}
