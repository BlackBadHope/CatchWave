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
    private MomentStore moments;
    private LibraryStore library;
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
        if(!MediaBridge.headphones(SyncService.this)){pauseOwned();finish(L.t("Music output changed"),L.t("Live Sync stopped: choose headphones. Regular catching is available for the speaker."));return;}
        main.postDelayed(this,1000);
    }};
    private final AudioDeviceCallback routeCallback=new AudioDeviceCallback(){
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            if(active&&model.live&&!MediaBridge.headphones(SyncService.this)) { pauseOwned();finish(L.t("Headphones disconnected"),L.t("Live Sync stopped. Connect headphones and start again.")); }
        }
    };
    @Override public void onCreate(){L.init(this);
        super.onCreate();bridge=new MediaBridge(this);moments=MomentStore.get(this);library=LibraryStore.get(this);
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,L.t("CatchWave · Music around you"),NotificationManager.IMPORTANCE_LOW));
        audioManager=getSystemService(AudioManager.class);audioManager.registerAudioDeviceCallback(routeCallback,main);
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent==null)return START_NOT_STICKY;
        if(OPEN_STARTED.equals(intent.getAction())){if(active){compatibleOpening=true;acquireStarted=lastLaunchRequest=SystemClock.elapsedRealtime();claimQueue(bridge.controller());model.record(L.t("Opening the selected recording through the YouTube Music screen"));}else stopSelf();return START_NOT_STICKY;}
        if(OPEN_FAILED.equals(intent.getAction())){finish(L.t("Could not open YouTube Music"),L.t("Check that the player is installed and try catching again."));return START_NOT_STICKY;}
        if(REJECT.equals(intent.getAction())){pauseOwned();if(model.track!=null){client.invalidate(model.track);MatchPins.remove(this,model.track.key);moments.removeNewest(model.track.key);}model.aligned=false;model.launchTicket++;finish(L.t("This is a different recording"),L.t("The wrong match was removed from the cache. Try again on another fragment or choose the right version in YouTube Music."));return START_NOT_STICKY;}
        if(USER_PAUSE.equals(intent.getAction())||USER_HOLD.equals(intent.getAction())||USER_RESUME.equals(intent.getAction())){
            if(active)userControl(intent.getAction());else stopSelf();return START_NOT_STICKY;
        }
        if(STOP.equals(intent.getAction())) {keepPlayerOnExit=true;finish(L.t("Session stopped"),L.t("Music control is yours again."));return START_NOT_STICKY;}
        if(ADJUST.equals(intent.getAction())) {seekAttempts=0;lastSeek=0;initialSeek=false;awaitingSeekSettle=false;return START_NOT_STICKY;}
        if(active)return START_NOT_STICKY;
        // Cancel both a running LAN receiver and an Activity-enqueued start before microphone mode.
        new LanSettings(this).prefs.edit().putBoolean("resume",false).apply();stopService(new Intent(this,LanAudioService.class));
        boolean live=intent.getBooleanExtra("live",false);
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED||!MediaBridge.allowed(this)) {
            model.update(L.t("Permissions required"),L.t("Allow the microphone and player control in the app."));stopSelf();return START_NOT_STICKY;
        }
        if(live&&!MediaBridge.headphones(this)){model.update(L.t("Connect headphones"),L.t("Live Sync must hear the external source, not the phone speaker."));stopSelf();return START_NOT_STICKY;}
        active=true;captureDone=false;model.running=true;model.live=live;model.track=null;model.aligned=false;model.needsOpen=false;model.manualHold=false;model.errorMs=Long.MAX_VALUE;
        model.resetTrace();model.record(L.t("Session started; correction ")+getSharedPreferences("settings",MODE_PRIVATE).getInt("adjustment",0)+L.t(" ms"));
        resolver.submit(moments::size); // Load the moments file off the main thread before the first confirmation.
        model.progress=0;started=lastLoud=SystemClock.elapsedRealtime();lastMatch=0;
        try {
            startForeground(NOTIFICATION,notification(L.t("Listening to music nearby…")),ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            wakeLock=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"CatchWave:listen");wakeLock.acquire(31*60*1000L);
            freshCapture();main.post(tick);if(live)main.postDelayed(routeCheck,1000);
        }catch(Exception e){finish(L.t("Could not turn on the microphone"),e.getMessage()==null?L.t("Open the app and try again."):e.getMessage());}
        return START_NOT_STICKY;
    }
    private Notification notification(String text){
        Intent open=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,SyncService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_wave).setContentTitle(model.live?"CatchWave · Live Sync":L.t("CatchWave · Music around you"))
            .setContentText(text).setContentIntent(pi).setOngoing(active).setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(null,L.t("Stop"),stop).build()).build();
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
        startForeground(NOTIFICATION,notification(L.t("Listening to the source…")),ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        captureGeneration++;captureDone=true;resumeFresh=true;
        cancelRequests();requests=new RequestScope();
        model.compatibleLaunchRequested=false;model.launchTicket++;compatibleOpening=false;
        if(capturePause!=null)capturePause.release();
        model.track=null;model.aligned=false;model.needsOpen=false;model.manualHold=false;model.errorMs=Long.MAX_VALUE;model.tEstMs=model.tSessionAfterSeekMs=model.seekLagMs=Long.MIN_VALUE;model.progress=0;
        captureStarted=lastLoud=quietStarted=SystemClock.elapsedRealtime();lastMatch=0;lastPlayerState=PlaybackState.STATE_NONE;playerError="";catalogProblem="";prefetchKey="";ownsQueue=false;keepPlayerOnExit=false;
        sourceLostAt=pauseRequestedAt=0;sourceResumeReady=false;pausedByUs=false;
        sourceTimeline.reset();shortCapturePreferred=false;learnedSeekLag=getSharedPreferences("settings",MODE_PRIVATE).getLong("seek_lag_ms",-1);awaitingSeekSettle=false;seekSentAt=seekCommanded=seekEstAtSend=0;model.needsAudioRefine=false;
        if(learnedSeekLag>=0)model.record(L.t("First seek: saved seek_lag=")+learnedSeekLag+L.t(" ms"));
        capturePause=new CapturePause(bridge.controller());
        if(!model.live)claimQueue(bridge.controller());
        model.record(L.t("Fresh measurement #")+captureGeneration+L.t("; OnePlus pause requested=")+capturePause.requested());
        model.update(L.t("Preparing the microphone"),capturePause.requested()?L.t("Pausing YouTube Music to hear the source."):L.t("Preparing a new source measurement…"));
        final int generation=captureGeneration;
        final RequestScope scope=requests;
        main.post(new Runnable(){public void run(){
            if(!active||generation!=captureGeneration)return;
            if(capturePause.ready()&&(microphone==null||!microphone.isAlive())){
                model.record(L.t("Pause confirmed; starting the microphone"));captureDone=false;
                model.update(L.t("Listening to the source"),L.t("Phone music paused. The first recognition uses 3 seconds of audio."));
                microphone=new Thread(()->capture(generation,scope),"CatchWave-microphone");microphone.start();
            }else if(SystemClock.elapsedRealtime()-quietStarted>=2000){finish(L.t("Could not prepare the recording"),L.t("Pause the phone's music and try catching again."));}
            else main.postDelayed(this,50);
        }});
    }
    private void capture(int generation,RequestScope scope){
        AudioRecord local=null;
        AtomicBoolean requesting=new AtomicBoolean();
        try {
            int min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(min<=0)throw new IllegalStateException(L.t("16 kHz recording is not supported by the device"));
            if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException(L.t("Microphone access revoked"));
            local=openMicrophone(Math.max(min*4,16000));
            recorder=local;if(local.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException(L.t("Microphone unavailable"));
            // Prefer the phone microphone even when output is a Bluetooth headset.
            for(AudioDeviceInfo device:audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) if(device.getType()==AudioDeviceInfo.TYPE_BUILTIN_MIC){local.setPreferredDevice(device);break;}
            local.startRecording();short[] ring=new short[96000],buffer=new short[1600];int index=0;long samples=0,lastSubmitted=0,lastSubmittedSamples=0,lastUi=0;
            while(active&&!captureDone&&!model.manualHold&&generation==captureGeneration){
                int n=local.read(buffer,0,buffer.length,AudioRecord.READ_BLOCKING);
                if(n<0)throw new IllegalStateException(L.t("Microphone interrupted (")+n+")");if(n==0)continue;
                long now=SystemClock.elapsedRealtime();double sum=0;
                for(int i=0;i<n;i++){ring[index]=buffer[i];index=(index+1)%ring.length;sum+=(double)buffer[i]*buffer[i];}
                samples+=n;double rms=Math.sqrt(sum/n)/32768;model.level=rms;
                if(rms>0.0025)lastLoud=now;
                if(now-lastUi>250){model.progress=(int)Math.min(100,samples*100/48000);model.notifyChanged();lastUi=now;}
                if(model.live&&model.track!=null&&now-lastLoud>1400)main.post(this::onSilence);
                int window=SyncMath.captureWindow(samples,lastSubmitted==0||shortCapturePreferred);
                long interval=SyncMath.recognitionInterval(model.track!=null,window);
                if(window>0 && (!shortCapturePreferred||samples-lastSubmittedSamples>=window) && now-lastSubmitted>=interval && rms>0.001 && requesting.compareAndSet(false,true)){
                    short[] sample=new short[window];for(int i=0;i<window;i++)sample[i]=ring[(index-window+ring.length+i)%ring.length];
                    if(!SyncMath.stableFragment(sample)){
                        requesting.set(false);lastSubmitted=now;lastSubmittedSamples=samples;
                        model.record(L.t("Fragment ")+window/16+L.t(" ms rejected: uneven loudness across the window, waiting for the next slice"));
                        continue;
                    }
                    long anchor=now-window/16;
                    AudioTimestamp timestamp=new AudioTimestamp();
                    int timestampResult=local.getTimestamp(timestamp,AudioTimestamp.TIMEBASE_BOOTTIME);
                    if(timestampResult==AudioRecord.SUCCESS) {
                        anchor=SyncMath.captureAnchor(timestamp.nanoTime,timestamp.framePosition,samples,window);
                    }
                    long endAge=now-anchor-window/16;
                    AudioDeviceInfo route=local.getRoutedDevice();
                    model.record(L.t("Microphone: timestamp=")+timestampResult+" frame="+timestamp.framePosition+" samples="+samples+" anchor="+anchor+" endAge="+endAge+L.t(" ms; route=")+(route==null?"?":route.getType())+" rms="+Math.round(rms*10000));
                    if(timestampResult!=AudioRecord.SUCCESS||endAge < -100||endAge>1000){requesting.set(false);main.post(()->{if(generation==captureGeneration)finish(L.t("No exact microphone timestamp"),L.t("Android did not return a reliable recording time. Try catching again."));});break;}
                    final long sampleAnchor=anchor;lastSubmitted=now;lastSubmittedSamples=samples;
                    model.record(L.t("Recognition: sent fragment ")+window/16+L.t(" ms"));
                    recognitionTask=network.submit(()->{try{recognize(sample,sampleAnchor,generation,scope);}finally{requesting.set(false);}});
                }
                if(model.track==null&&now-captureStarted>35000){main.post(()->{if(active&&generation==captureGeneration)finish(sourceTimeline.hasSamples()?L.t("Source position is unstable"):L.t("Could not recognize"),sourceTimeline.hasSamples()?L.t("Three consistent measurements were not obtained in 35 seconds. Check continuous playback and the recording version, then try again."):L.t("Move the phone closer to the music and try again."));});break;}
            }
        }catch(Exception e){if(active&&!captureDone&&generation==captureGeneration)main.post(()->{if(generation==captureGeneration)finish(L.t("Recording stopped"),e.getMessage()==null?L.t("Microphone unavailable"):e.getMessage());});}
        finally {if(local!=null){try{local.stop();}catch(Exception ignored){}local.release();}recorder=null;}
    }
    private void recognize(short[] sample,long anchor,int generation,RequestScope scope){
        try {
            if(!active||generation!=captureGeneration)return;
            long requestAt=SystemClock.elapsedRealtime();
            // The phone's own library answers first: offline, free, nothing leaves the device.
            if(getSharedPreferences("settings",MODE_PRIVATE).getBoolean("localFirst",true)){
                Track local=library.recognize(sample,anchor);
                if(local!=null){
                    if(!active||generation!=captureGeneration)return;
                    model.record(L.t("Local library match; processing=")+(SystemClock.elapsedRealtime()-requestAt)+L.t(" ms"));
                    shortCapturePreferred=true;
                    main.post(()->{if(generation==captureGeneration)accept(local);});
                    return;
                }
            }
            Track match=client.recognize(sample,anchor,scope);
            if(!active||generation!=captureGeneration)return;
            if(match==null||!match.hasOffset()&&sample.length<96000){shortCapturePreferred=false;model.record(L.t("Fragment without offset; processing=")+(SystemClock.elapsedRealtime()-requestAt)+L.t(" ms"));main.post(()->{if(active&&generation==captureGeneration&&model.track==null)model.update(L.t("Listening a bit longer"),L.t("Refining the recording and its position from 6 seconds of audio."));});return;}
            if(!match.hasOffset()){shortCapturePreferred=false;model.record(L.t("6 s without offset, waiting for the next independent slice; processing=")+(SystemClock.elapsedRealtime()-requestAt)+L.t(" ms"));return;}
            if(Math.abs(match.timeSkew)>=0.005){shortCapturePreferred=false;model.record(L.t("Catalog timeskew=")+match.timeSkew+L.t(" on this slice; offset=")+match.offsetMs+L.t(" kept for the scale, next fragment is longer"));}
            shortCapturePreferred=match.hasOffset()&&Math.abs(match.timeSkew)<0.005;
            model.record(L.t("Recognized ")+match.title+" / "+match.artist+"; offset="+match.offsetMs+L.t(" ms, skew=")+match.timeSkew+L.t(" processing=")+(SystemClock.elapsedRealtime()-requestAt)+L.t(" ms"));
            if(model.track!=null&&model.track.key.equals(match.key)){match.youtubeUrl=model.track.youtubeUrl;match.playbackTitle=model.track.playbackTitle;match.playbackArtist=model.track.playbackArtist;}
            else match.youtubeUrl=client.cached(match);
            main.post(()->{if(generation==captureGeneration)accept(match);});
        }catch(Exception e){if(scope.isCancelled())return;main.post(()->{if(active&&generation==captureGeneration&&!model.manualHold){pauseOwned();finish(L.t("Recognition unavailable"),e.getMessage()==null?L.t("Check the internet connection and try again."):e.getMessage());}});}
    }
    private void accept(Track match){
        if(!active||model.manualHold)return;
        long now=SystemClock.elapsedRealtime();
        if(lastLoud>0&&now-lastLoud>1400){if(model.live)onSilence();sourceTimeline.reset();model.update(L.t("The source went quiet"),L.t("Waiting for continuous playback for new measurements."));return;}
        // A delayed match from before the source went quiet cannot authorize resume.
        if(model.live&&pausedByUs&&(match.anchorMs<sourceLostAt||now-lastLoud>1400)){
            model.record(L.t("Old fragment after the source pause skipped"));return;
        }
        if(!match.hasOffset()){model.record(L.t("Fragment without offset skipped, capture continues"));return;}
        if(waitingNextSource&&model.track!=null&&model.track.key.equals(match.key)&&match.positionAt(now,0)>=endedSourcePosition-2000){model.record(L.t("A finished recording does not allow the next track in the queue"));return;}
        SourceTimeline.Result confirmation=sourceTimeline.offer(match);
        model.record(L.t("Source check: ")+confirmation.state+" n="+confirmation.count+" spread="+confirmation.spreadMs+L.t(" ms span=")+confirmation.spanMs+L.t(" ms rate=")+confirmation.rate);
        if(confirmation.state==SourceTimeline.State.IGNORED)return;
        if(confirmation.state==SourceTimeline.State.RATE_MISMATCH){pauseOwned();finish(L.t("Source speed differs"),L.t("Several measurements show a growing time drift. Check 1× speed and the recording version; a constant correction will not fix this."));return;}
        if(confirmation.state!=SourceTimeline.State.CONFIRMED){
            if(model.live&&model.track!=null&&!model.track.key.equals(match.key)){
                pauseOwned();model.compatibleLaunchRequested=false;model.needsOpen=false;model.launchTicket++;
            }
            if(confirmation.count>=1&&match.hasPosition()&&microphone!=null&&microphone.isAlive())prefetchCatalog(match);
            model.update(L.t("Checking position · ")+confirmation.count+"/3",match.title+L.t(" — comparing different fragments and the source's time progression."));return;
        }
        match=confirmation.track;
        if(match.youtubeUrl==null||match.youtubeUrl.isEmpty())match.youtubeUrl=client.cached(match);
        MatchPins.Pin pinned=MatchPins.get(this,match.key);
        if(pinned!=null&&!pinned.url.equals(match.youtubeUrl)){
            client.pin(match,new RecognitionClient.Candidate(pinned.title.isEmpty()?match.playbackTitle:pinned.title,
                pinned.artist.isEmpty()?match.playbackArtist:pinned.artist,pinned.url));
            model.record(L.t("Pinned recording applied"));
        }
        if(!model.live)captureDone=true;
        boolean changed=resumeFresh||waitingNextSource||model.track==null||!model.track.key.equals(match.key);
        if(changed||waitingNextSource){closeEndGuard();waitingNextSource=false;}
        if(changed&&!model.manualHold&&model.live&&model.track!=null)pauseOwned();
        model.track=match;lastMatch=match.anchorMs+match.sampleDurationMs;matchCount++;
        if(model.manualHold){model.update(L.t("Manual control"),L.t("A pause or seek suspended sync. Press «Sync» to continue."));return;}
        resumeFresh=false;
        if(changed){
            moments.add(match.key,match.title,match.artist,match.youtubeUrl,match.positionAt(now,0));
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
                model.record(L.t("Start command sent; waiting for the recording to confirm"));
                nativeDeadline=acquireStarted+4000;model.needsOpen=false;
                model.update(L.t("Found: ")+match.title,L.t("YouTube Music is opening the recording. Catching the current moment…"));
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
        if(!MediaBridge.supports(player,PlaybackState.ACTION_PLAY)){finish(L.t("The player does not allow resuming"),L.t("Catch again after the music starts."));return;}
        acquireStarted=lastLaunchRequest=now;playRequested=true;initialSeek=false;awaitingSeekSettle=false;lastSeek=0;seekAttempts=0;
        pausedByUs=false;sourceResumeReady=false;pauseRequestedAt=sourceLostAt=0;model.aligned=false;
        player.getTransportControls().play();model.record(L.t("Resuming the source: new player wait deadline"));
        model.update(L.t("Resuming the music"),L.t("Waiting for playback confirmation, then refining the position."));
    }
    private void resolveLink(Track match){
        if(resolveRequested)return;resolveRequested=true;
        final int generation=captureGeneration;
        final RequestScope scope=requests;
        model.record(L.t("Fallback: searching for a direct YouTube Music link"));
        model.update(L.t("Found: ")+match.title,L.t("Searching for a direct link to open the player…"));
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
                    catalogProblem=result.message;model.record(L.t("Search: ")+result.kind+" · "+catalogProblem);
                    finish(result.kind==RecognitionClient.Kind.NO_MATCH?L.t("No matching recording found"):L.t("Recording search unavailable"),catalogProblem+L.t(" Press «Catch» to take a new measurement."));return;
                }
                model.track.youtubeUrl=resolved;
                if(!resolved.isEmpty())moments.updateUrl(match.key,resolved);
                model.track.playbackTitle=match.playbackTitle;model.track.playbackArtist=match.playbackArtist;
                model.record(L.t("Catalog: ")+match.playbackTitle+" / "+match.playbackArtist+L.t("; link=")+resolved);
                if(!resolved.isEmpty()&&getSharedPreferences("settings",MODE_PRIVATE).getBoolean("compatibleLaunch",false)){
                    compatibleOpening=true;ownsQueue=true;model.needsOpen=true;model.compatibleLaunchRequested=true;
                    model.update(L.t("Starting the found recording"),L.t("YouTube Music will open briefly. After the timecode is set, we return to «CatchWave»."));
                    notifyStatus(L.t("Open «CatchWave» to start the found recording"));return;
                }
                boolean remote=false;if(!resolved.isEmpty())try{lastLaunchRequest=SystemClock.elapsedRealtime();remote=bridge.requestTrack(model.track);if(remote)claimQueue(bridge.controller());}catch(Exception ignored){}
                model.needsOpen=!remote;
                model.record(resolved.isEmpty()?L.t("The same recording was not found in the catalog"):L.t("Direct link found; command sent=")+remote);
                model.update(L.t("Found: ")+model.track.title,remote?L.t("The player is starting the recording in the background…"):L.t("The player needs to be opened manually. Press «Open YouTube Music», then come back here."));
                notifyStatus(model.needsOpen&&!MainActivity.visible?L.t("Tap to open the found song."):model.track.title);
            });
        });
    }
    private void onSilence(){
        if(active&&!model.manualHold&&!resumeFresh&&model.live&&SystemClock.elapsedRealtime()-lastLoud>1400&&!pausedByUs&&model.aligned){
            sourceLostAt=SystemClock.elapsedRealtime();sourceResumeReady=false;
            sourceTimeline.reset();
            pauseOwned();model.update(L.t("The source went quiet"),L.t("Player paused. Waiting for a new confident match."));notifyStatus(L.t("The source went quiet · waiting"));
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
        try{syncTick();}catch(SecurityException e){finish(L.t("Player access is off"),L.t("Enable notification access for «CatchWave»."));}
        if(active)main.postDelayed(this,200);
    }};
    private void syncTick(){
        long now=SystemClock.elapsedRealtime();
        if(now-started>30*60*1000){pauseOwned();finish(L.t("Session ended"),L.t("A single Live Sync session is limited to 30 minutes. You can start it again."));return;}
        if(model.manualHold)return;
        restrainQueue();
        if(resumeFresh)return;
        Track t=model.track;
        if(t==null)return;
        if(endGuard!=null){
            TrackEndGuard.State boundary=endGuard.check(now);
            if(boundary==TrackEndGuard.State.FAILED){model.aligned=false;finish(L.t("Could not confirm the pause at the end of the track"),L.t("Check YouTube Music's state. The app did not confirm that the queue stopped."));return;}
            if(boundary==TrackEndGuard.State.STOPPED){
                closeEndGuard();model.aligned=false;model.record(L.t("End of recording: YouTube Music pause confirmed"));
                if(!model.live){finish(L.t("Track finished — music paused"),L.t("Not starting the next track in the queue. Press «Catch» for a new song."));return;}
                waitingNextSource=true;endedSourcePosition=t.positionAt(now,0);sourceTimeline.reset();
                sourceLostAt=now;sourceResumeReady=false;pausedByUs=true;pauseRequestedAt=0;
                model.update(L.t("Waiting for the source's next track"),L.t("The YouTube Music queue is paused. Confirming the next song with the microphone."));return;
            }
            if(boundary==TrackEndGuard.State.PAUSING){model.update(L.t("Stopping the player queue"),L.t("Waiting for YouTube Music to confirm the pause."));return;}
            if(model.aligned&&!model.live)return;
        }
        if(waitingNextSource)return;
        if(model.live&&now-lastMatch>16000&&!pausedByUs&&model.aligned){sourceLostAt=now;sourceResumeReady=false;sourceTimeline.reset();pauseOwned();model.update(L.t("Source lost"),L.t("Music paused. Still looking for a confirmed match."));}
        if(model.live&&sourceTimeline.hasSamples()&&!sourceTimeline.isTracking(t.key)){if(!pausedByUs)pauseOwned();return;}
        MediaController c=bridge.controller();
        PlaybackState p=c==null?null:c.getPlaybackState();
        if(p!=null&&p.getState()!=lastPlayerState){lastPlayerState=p.getState();MediaMetadata m=c.getMetadata();model.record("YTM state="+p.getState()+" pos="+p.getPosition()+" updated="+p.getLastPositionUpdateTime()+" speed="+p.getPlaybackSpeed()+" metadata="+(m==null?"?":m.getDescription()));}
        if(p!=null&&p.getState()==PlaybackState.STATE_ERROR&&p.getLastPositionUpdateTime()>=lastLaunchRequest){
            playerError=String.valueOf(p.getErrorMessage());model.aligned=false;
            if(!resolveRequested&&!compatibleOpening){model.record(L.t("YouTube Music error: ")+playerError);resolveLink(t);}
            model.update(L.t("YouTube Music did not start the recording"),playerError+L.t(". Trying a direct link; if that fails, manual selection is needed."));timeoutAcquire(now);return;
        }
        if(c==null){if(!compatibleOpening&&(nativeDeadline==0||now>=nativeDeadline))resolveLink(t);model.update(L.t("Waiting for YouTube Music"),L.t("Open the found song. Notification access must be enabled."));timeoutAcquire(now);return;}
        if(!MediaBridge.isTrack(c,t)){
            if(!compatibleOpening&&nativeDeadline>0&&now>=nativeDeadline)resolveLink(t);
            model.aligned=false;model.update(L.t("The player has not opened the found recording yet"),t.title+" / "+t.artist+L.t(". If a different song was recognized, catch again on another fragment."));timeoutAcquire(now);return;
        }
        if(!model.live)armQueueGuard(c,t);
        if(!t.hasPosition()){finish(L.t("Song found, exact position unavailable"),L.t("The service did not return a usable timecode, or the source is changing playback speed."));return;}
        if(!MediaBridge.supports(c,PlaybackState.ACTION_SEEK_TO)){finish(L.t("The player does not allow seeking"),L.t("YouTube Music did not declare seek support for this recording. Check the content type and account restrictions."));return;}
        if(p==null){timeoutAcquire(now);return;}
        if(pausedByUs){
            if(p.getState()==PlaybackState.STATE_PAUSED&&(pauseRequestedAt==0||p.getLastPositionUpdateTime()>=pauseRequestedAt)){pauseRequestedAt=0;resumeOwnedIfReady(c,now);}
            else if(pauseRequestedAt>0&&now-pauseRequestedAt>2000)finish(L.t("The player did not confirm the pause"),L.t("Stop the phone's music and try catching again."));
            return;
        }
        if(p.getState()!=PlaybackState.STATE_PLAYING){
            if(!compatibleOpening&&p.getState()==PlaybackState.STATE_ERROR&&now>=nativeDeadline)resolveLink(t);
            if(!initialSeek&&!playRequested&&p.getState()==PlaybackState.STATE_PAUSED&&MediaBridge.supports(c,PlaybackState.ACTION_PLAY)){
                playRequested=true;c.getTransportControls().play();model.record(L.t("Playback of the matched recording requested"));
            }
            timeoutAcquire(now);return;
        }
        if(!Float.isFinite(p.getPlaybackSpeed())||Math.abs(p.getPlaybackSpeed()-1f)>0.005f){finish(L.t("The player is changing speed"),L.t("Sync needs normal 1× playback speed."));return;}
        long tEst=estimatePosition(t,now);
        MediaMetadata meta=c.getMetadata();long duration=meta==null?0:meta.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if(duration>0&&tEst>=duration-500){finish(L.t("Fragment outside the recording"),L.t("Song versions may differ. Try again on the next fragment."));return;}
        long tSession=SyncMath.playerPosition(p.getPosition(),p.getLastPositionUpdateTime(),p.getPlaybackSpeed(),now,true);
        if(initialSeek&&tSession<0){timeoutAcquire(now);return;}
        long delta=tSession<0?Long.MAX_VALUE:SeekClock.delta(tEst,tSession);model.errorMs=delta;
        if(awaitingSeekSettle){
            if(!SeekClock.settled(now,seekSentAt,p.getLastPositionUpdateTime(),tSession,seekCommanded)){
                if(now-seekSentAt>5000)finish(L.t("Could not confirm sync"),L.t("MediaSession did not confirm the seek. Catch again or check the recording version."));
                return;
            }
            long lag=SeekClock.lag(seekSentAt,p.getLastPositionUpdateTime());
            rememberSeekLag(lag);
            awaitingSeekSettle=false;
            model.tEstMs=tEst;model.tSessionAfterSeekMs=tSession;model.seekLagMs=lag;
            model.record("t_est="+tEst+" t_est_at_send="+seekEstAtSend+" t_session_after_seek="+tSession+" Δ="+delta+" seek_lag="+lag+" learned_lag="+learnedSeekLag);
            model.record(L.t("Refine by sound: ")+(model.live?L.t("does not run in Live"):L.t("available after catching if Android allows capture")));
            if(Math.abs(delta)<=SyncMath.TOLERANCE_MS){confirmAligned(c,t);return;}
            if(seekAttempts<3){issueSeek(c,tEst,tSession,now,duration);return;}
            finish(L.t("Could not confirm sync"),L.t("After seek_lag compensation the player still differs from the estimate. This is not a speaker measurement; with regular catching press «Refine by sound»."));return;
        }
        if(!initialSeek||SyncMath.shouldSeek(delta,now-lastSeek,seekAttempts)){
            issueSeek(c,tEst,tSession,now,duration);return;
        }
        if(now-lastSeek<SeekClock.SETTLE_MS)return;
        if(Math.abs(delta)<=SyncMath.TOLERANCE_MS&&p.getLastPositionUpdateTime()>=lastSeek)confirmAligned(c,t);
        else if(now-lastSeek>5000&&(seekAttempts>=3||Math.abs(delta)<=SyncMath.TOLERANCE_MS))finish(L.t("Could not confirm sync"),L.t("The player did not confirm the target timecode. Catch again or check the recording version."));
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
        model.record(L.t("Seek #")+seekAttempts+": t_est="+tEst+" commanded="+commanded+" seek_lag_comp="+(learnedSeekLag<0?0:learnedSeekLag)+" t_session="+tSession+" updated="+(state==null?"?":state.getLastPositionUpdateTime()));
        model.update(L.t("Aligning the position"),L.t("Waiting for MediaSession to settle. 3×≤120 ms is recognizer consistency, not audible sync."));
    }
    private void confirmAligned(MediaController c,Track t){
        model.aligned=true;
        claimQueue(c);armQueueGuard(c,t);
        model.update(model.live?L.t("Live Sync active"):L.t("Timecode set"),model.live?L.t("Watching measurement consistency and source speed."):L.t("Position verified by several measurements (3×≤120 ms is recognizer consistency, not audible sync). YouTube Music confirmed the timecode."));
        if(!model.live){
            cancelRequests();model.level=0;model.guardingTrack=true;model.needsAudioRefine=true;
            startForeground(NOTIFICATION,notification(L.t("At the end of this recording I will pause the music")),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            model.update(L.t("Timecode set · single track"),L.t("Microphone off. Comparing YouTube Music's internal audio with the microphone if Android allows capture. The queue will stop at the end of the recording."));
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
                if(MediaBridge.pausePlayer(c))model.record(t==null?L.t("Queue stopped while catching"):L.t("Queue: another recording was paused"));
            }
            return;
        }
        if(t==null&&MediaBridge.pausePlayer(c))model.record(L.t("Queue stopped while catching"));
    }
    private void armQueueGuard(MediaController c,Track t){
        if(endGuard!=null||c==null||t==null)return;
        endGuard=new TrackEndGuard(c,t,main,()->{if(active)try{syncTick();}catch(SecurityException e){finish(L.t("Player access is off"),L.t("Enable notification access for «CatchWave»."));}});
        model.record(L.t("YouTube Music queue control enabled"));
    }
    private void haltOwnedQueue(){
        if(!ownsQueue||model.live||keepPlayerOnExit)return;
        MediaController c=bridge.controller();
        MediaBridge.holdQueue(c);
        if(MediaBridge.isTrack(c,model.track)){ownsQueue=false;return;}
        if(MediaBridge.pausePlayer(c))model.record(L.t("YouTube Music queue stopped"));
        ownsQueue=false;
    }
    private static boolean isPlayerError(MediaController c){PlaybackState p=c==null?null:c.getPlaybackState();return p!=null&&p.getState()==PlaybackState.STATE_ERROR;}
    private void timeoutAcquire(long now){if(now-acquireStarted>20000)finish(L.t("Could not open the matched recording"),playerError.isEmpty()?L.t("The recognizer and YouTube Music may pick different versions. Check the found song, open the right recording and catch again."):"YouTube Music: "+playerError+L.t(" Open the recording manually and catch again."));}
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
            model.update(L.t("Manual control"),L.t("Listening and search paused. Press «Sync» for a new measurement."));
        }
        model.record(L.t("Control: ")+action.substring(action.lastIndexOf('.')+1));
    }
    private void finish(String title,String detail){
        stopService(new Intent(this,AudioCalibrationService.class));
        closeEndGuard();
        cancelRequests();
        if(keepPlayerOnExit){
            if(capturePause!=null&&capturePause.restore(bridge.controller()))model.record(L.t("Previous playback restored"));
        }else {
            if(capturePause!=null)capturePause.release();
            haltOwnedQueue();
        }
        model.record(title+L.t("; timecode error=")+model.errorMs+L.t(" ms"));
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
    @Override public void onTaskRemoved(Intent rootIntent){finish(L.t("Session stopped"),L.t("The app was closed."));}
    private void prefetchCatalog(Track match){
        if(match==null||match.key.isEmpty()||match.key.equals(prefetchKey)||!client.cached(match).isEmpty())return;
        prefetchKey=match.key;
        final RequestScope scope=requests;
        final int generation=captureGeneration;
        resolver.submit(()->{
            RecognitionClient.Resolution result=client.resolveResult(match,scope);
            if(!active||generation!=captureGeneration)return;
            if(result.kind==RecognitionClient.Kind.FOUND&&result.url!=null&&!result.url.isEmpty())moments.updateUrl(match.key,result.url);
            model.record(L.t("Catalog in advance: ")+result.kind+(result.url==null||result.url.isEmpty()?"":" "+result.url));
        });
    }
    private AudioRecord openMicrophone(int buffer){
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException(L.t("Microphone access revoked"));
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
