package app.catchwave;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.Locale;

public final class MainActivity extends Activity {
    public static volatile boolean visible;
    private static final int BG=CatchWaveUi.BG,PANEL=CatchWaveUi.PANEL,TEXT=CatchWaveUi.TEXT,MUTED=CatchWaveUi.MUTED,ACCENT=CatchWaveUi.ACCENT;
    private final SessionModel model=SessionModel.INSTANCE;
    private final Runnable listener=this::render;
    private TextView status,detail,track,delta,setup,modeNote;
    private Button start,open,reject,calibrate,sync;
    private Switch live;
    private WaveView wave;
    private ProgressBar progress;
    private boolean pendingStart,refinePrompted;
    RecognitionClient recognition=new RecognitionClient();
    private final java.util.concurrent.ExecutorService lookup=java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile RequestScope lookupScope;
    private LinearLayout playerPanel;
    private TextView playerTitle,playerClock;
    private TextView playerFailure;
    private Button retryPlayer;
    private Button playPause;
    private SeekBar timeline;
    private boolean dragging;
    private PlayerLaunch playerLaunch;
    private final Handler playerHandler=new Handler(Looper.getMainLooper());
    private final Runnable playerTick=new Runnable(){public void run(){renderPlayer();playerHandler.postDelayed(this,500);}};
    @Override public void onCreate(Bundle state){L.init(this);
        super.onCreate(state);playerLaunch=new PlayerLaunch(this,model);buildUi();model.add(listener);render();
        if(getIntent().getBooleanExtra("openSettings",false))new Handler(Looper.getMainLooper()).post(this::settings);
    }
    private void buildUi(){
        LinearLayout body=CatchWaveUi.column(this);CatchWaveUi.page(this,body);CatchWaveUi.header(this,body,()->CatchWaveUi.home(this));
        TextView heading=text(L.t("Music around you"),28,TEXT);heading.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));add(body,heading,24);
        TextView intro=text(L.t("Recognize a song and continue\nfrom the same moment in YouTube Music."),15,MUTED);add(body,intro,8);
        wave=new WaveView(this);LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(-1,dp(120));wp.topMargin=dp(8);body.addView(wave,wp);
        status=text("",20,TEXT);status.setTypeface(null,Typeface.BOLD);status.setGravity(Gravity.CENTER);add(body,status,0);
        detail=text("",14,MUTED);detail.setGravity(Gravity.CENTER);detail.setLineSpacing(dp(3),1);add(body,detail,8);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,dp(3));pp.topMargin=dp(18);body.addView(progress,pp);
        track=text("",16,TEXT);track.setPadding(dp(16),dp(14),dp(16),dp(14));track.setBackground(shape(PANEL,16));add(body,track,18);
        delta=text("",12,MUTED);delta.setGravity(Gravity.CENTER);add(body,delta,6);
        buildPlayer(body);
        calibrate=button(L.t("Refine by sound"),PANEL,ACCENT);calibrate.setOnClickListener(v->calibrateAudio());add(body,calibrate,12);
        start=button(L.t("Catch"),ACCENT,BG);start.setTextSize(18);start.setTypeface(null,Typeface.BOLD);start.setOnClickListener(v->{if(model.measuringAudio)stopService(new Intent(this,AudioCalibrationService.class));else if(model.running)stop();else begin();});
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,dp(60));bp.topMargin=dp(18);body.addView(start,bp);
        LinearLayout mode=new LinearLayout(this);mode.setOrientation(LinearLayout.VERTICAL);mode.setPadding(0,dp(10),0,dp(14));CatchWaveUi.divider(body,20);
        live=new Switch(this);live.setText("Live Sync");live.setTextColor(TEXT);live.setTextSize(17);live.setPadding(0,dp(6),0,dp(6));
        CatchWaveUi.styleSwitch(live);
        live.setChecked(getPreferences().getBoolean("live",false));live.setOnCheckedChangeListener((b,checked)->{getPreferences().edit().putBoolean("live",checked).apply();modeNote.setText(checked?L.t("Follow pauses and song changes. Headphones required."):L.t("One catch — then it's on its own"));});mode.addView(live);
        modeNote=text(live.isChecked()?L.t("Follow pauses and song changes. Headphones required."):L.t("One catch — then it's on its own"),13,MUTED);modeNote.setLineSpacing(dp(2),1);mode.addView(modeNote);add(body,mode,16);
        open=button(L.t("Open YouTube Music"),PANEL,TEXT);open.setOnClickListener(v->openTrack());add(body,open,10);
        reject=button(L.t("This is a different version"),PANEL,MUTED);reject.setOnClickListener(v->rejectMatch());add(body,reject,8);
        setup=text("",12,MUTED);setup.setGravity(Gravity.CENTER);setup.setPadding(0,dp(8),0,0);setup.setOnClickListener(v->permissionsInfo());add(body,setup,8);
        CatchWaveUi.divider(body,20);
        Button moments=button(L.t("Music moments"),PANEL,TEXT);moments.setOnClickListener(v->startActivity(new Intent(this,MomentsActivity.class)));add(body,moments,16);
        Button options=button(L.t("Recognition settings"),PANEL,TEXT);options.setOnClickListener(v->settings());add(body,options,10);
        TextView footer=text(AppIdentity.label()+L.t(" · experimental version"),11,MUTED);footer.setGravity(Gravity.CENTER);add(body,footer,18);
    }
    private android.content.SharedPreferences getPreferences(){return getSharedPreferences("settings",MODE_PRIVATE);}
    private void begin(){
        new LanSettings(this).prefs.edit().putBoolean("resume",false).apply();stopService(new Intent(this,LanAudioService.class));
        pendingStart=true;
        if(!getPreferences().getBoolean("intro",false)){
            CatchWaveUi.dialog(this).setTitle(L.t("How catching works"))
                .setMessage(RecognitionPath.explanation()+L.t("\n\nThe recording itself stays in RAM and is not saved. There is no advertising ID, analytics or built-in ads.\n\nSeeking needs media session access through the «Notification access» permission. The app does not read notification contents.\n\nLive Sync currently works only with headphones."))
                .setPositiveButton(L.t("Continue"),(d,w)->{getPreferences().edit().putBoolean("intro",true).apply();begin();})
                .setNegativeButton(L.t("Later"),(d,w)->pendingStart=false).show();return;
        }
        if(!youtubeInstalled()){pendingStart=false;CatchWaveUi.dialog(this).setTitle(L.t("YouTube Music required")).setMessage(L.t("Install YouTube Music and sign in to your account, then return to «CatchWave».")).setPositiveButton(L.t("Got it"),null).show();return;}
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},10);return;}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED&&!getPreferences().getBoolean("notificationAsked",false)){
            getPreferences().edit().putBoolean("notificationAsked",true).apply();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},11);return;
        }
        if(!MediaBridge.allowed(this)){
            pendingStart=false;CatchWaveUi.dialog(this).setTitle(L.t("Allow player control"))
                .setMessage(L.t("In the next screen, turn on «CatchWave». This gives the app access to the YouTube Music media session to read the position, seek and pause. Then come back and press «Catch»."))
                .setPositiveButton(L.t("Open settings"),(d,w)->openNotificationSettings()).setNegativeButton(L.t("Later"),null).show();return;
        }
        if(live.isChecked()&&!MediaBridge.headphones(this)){pendingStart=false;CatchWaveUi.dialog(this).setTitle(L.t("Live Sync needs headphones")).setMessage(MediaBridge.outputLabel(this)+L.t(".\n\nChoose headphones for music playback. With the speaker, regular catching is available: turn off Live Sync. Continuous following through the speaker cannot yet separate its own music from the external one.")).setPositiveButton(L.t("Got it"),null).show();return;}
        pendingStart=false;startForegroundService(new Intent(this,SyncService.class).setAction(SyncService.START).putExtra("live",live.isChecked()));
    }
    private boolean youtubeInstalled(){try{getPackageManager().getApplicationInfo(MediaBridge.PACKAGE,0);return true;}catch(PackageManager.NameNotFoundException e){return false;}}
    private void stop(){startService(new Intent(this,SyncService.class).setAction(SyncService.STOP));}
    private void openNotificationSettings(){
        Intent intent=new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        try{startActivity(intent);}catch(ActivityNotFoundException e){startActivity(new Intent(Settings.ACTION_SETTINGS));}
    }
    private void openTrack(){
        Track t=model.track;if(t==null)return;
        model.needsOpen=false;model.compatibleLaunchRequested=false;
        try{
            if(model.running)startService(new Intent(this,SyncService.class).setAction(SyncService.OPEN_STARTED));
            playerLaunch.open(t,model.running);
        }catch(ActivityNotFoundException|SecurityException e){if(model.running)startService(new Intent(this,SyncService.class).setAction(SyncService.OPEN_FAILED));else model.update(L.t("YouTube Music unavailable"),L.t("Install the app and try catching again."));}
    }
    private void render(){
        if(status==null||isFinishing())return;
        status.setText(model.status);detail.setText(model.detail);start.setText(model.measuringAudio?L.t("Cancel refinement"):model.running?L.t("Stop session"):L.t("Catch"));live.setEnabled(!model.running);
        calibrate.setVisibility(model.guardingTrack&&!model.live?View.VISIBLE:View.GONE);calibrate.setEnabled(!model.measuringAudio);
        sync.setEnabled(!model.measuringAudio);open.setEnabled(!model.measuringAudio);reject.setEnabled(!model.measuringAudio);
        if(model.measuringAudio||model.running&&!model.guardingTrack)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        progress.setVisibility(model.running&&model.track==null?View.VISIBLE:View.INVISIBLE);progress.setProgress(model.progress);
        wave.level=model.level;wave.listening=model.running;wave.aligned=model.aligned;wave.invalidate();
        Track current=model.track;track.setVisibility(current==null?View.GONE:View.VISIBLE);delta.setVisibility(current==null?View.GONE:View.VISIBLE);open.setVisibility(current==null?View.GONE:View.VISIBLE);
        reject.setVisibility(current==null?View.GONE:View.VISIBLE);
        if(current!=null){
            track.setText(current.title+"\n"+current.artist);
            delta.setText(model.errorMs==Long.MAX_VALUE?L.t("Waiting for the player position"):String.format(Locale.ROOT,L.t("Player timecode Δ: %+.0f ms%s\nThis is not an acoustic latency measurement. 3×≤120 ms is recognizer consistency."),(double)model.errorMs,model.seekLagMs==Long.MIN_VALUE?"":String.format(Locale.ROOT,L.t(" · seek_lag %d ms"),model.seekLagMs)));
            if(Double.isFinite(model.audioLagMs))delta.setText(String.format(Locale.ROOT,L.t("Internal audio to microphone: %+.1f ms\nSpeaker latency not measured."),model.audioLagMs));
        }
        setup.setText((checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED?"●":"○")+L.t(" Microphone    ")+(MediaBridge.allowed(this)?"●":"○")+L.t(" Player\n")+MediaBridge.outputLabel(this));
        renderPlayer();
        if(model.manualHold){model.compatibleLaunchRequested=false;playerLaunch.cancel();}
        if(visible&&model.running&&!model.manualHold&&model.compatibleLaunchRequested&&getPreferences().getBoolean("compatibleLaunch",false))openTrack();
        if(!model.running)refinePrompted=false;
        if(visible&&model.needsAudioRefine&&!model.measuringAudio&&!refinePrompted&&!model.live){
            MediaController player=new MediaBridge(this).controller();
            if(new CalibrationTarget(player,model.track).valid(player,model)){
                refinePrompted=true;model.needsAudioRefine=false;calibrateAudio();
            }
        }
    }
    private void buildPlayer(LinearLayout parent){
        playerPanel=new LinearLayout(this);playerPanel.setOrientation(LinearLayout.VERTICAL);playerPanel.setPadding(dp(16),dp(12),dp(16),dp(12));playerPanel.setBackground(shape(PANEL,18));
        playerTitle=text("YouTube Music",14,TEXT);playerPanel.addView(playerTitle);
        playerClock=text("",12,MUTED);add(playerPanel,playerClock,6);
        timeline=new SeekBar(this);timeline.setContentDescription(L.t("Song position"));timeline.setMax(1000);timeline.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        timeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar bar){dragging=true;if(model.running){model.manualHold=true;startService(new Intent(MainActivity.this,SyncService.class).setAction(SyncService.USER_HOLD));}}
            public void onProgressChanged(SeekBar bar,int value,boolean user){}
            public void onStopTrackingTouch(SeekBar bar){
                MediaController c=new MediaBridge(MainActivity.this).controller();MediaMetadata m=c==null?null:c.getMetadata();long duration=m==null?0:m.getLong(MediaMetadata.METADATA_KEY_DURATION);
                if(duration>0&&MediaBridge.supports(c,PlaybackState.ACTION_SEEK_TO))c.getTransportControls().seekTo(duration*bar.getProgress()/1000);
                dragging=false;
            }
        });add(playerPanel,timeline,4);
        LinearLayout buttons=row();playPause=button(L.t("Start"),ACCENT,BG);playPause.setContentDescription(L.t("Play or pause"));playPause.setOnClickListener(v->togglePlayback());
        buttons.addView(playPause,new LinearLayout.LayoutParams(dp(100),dp(48)));
        sync=button(L.t("Sync"),PANEL,ACCENT);sync.setTextSize(14);sync.setOnClickListener(v->{if(model.running)startService(new Intent(this,SyncService.class).setAction(SyncService.USER_RESUME));else begin();});
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,dp(46),1);sp.leftMargin=dp(8);buttons.addView(sync,sp);add(playerPanel,buttons,4);
        playerFailure=text("",13,TEXT);add(playerPanel,playerFailure,8);
        retryPlayer=button(L.t("Catch again"),PANEL,ACCENT);retryPlayer.setOnClickListener(v->{if(model.running)startService(new Intent(this,SyncService.class).setAction(SyncService.USER_RESUME));else begin();});add(playerPanel,retryPlayer,6);
        add(parent,playerPanel,12);
    }
    private void togglePlayback(){
        MediaController c=new MediaBridge(this).controller();PlaybackState p=c==null?null:c.getPlaybackState();if(p==null)return;
        boolean playing=p.getState()==PlaybackState.STATE_PLAYING;
        if(model.running){if(playing)model.manualHold=true;startService(new Intent(this,SyncService.class).setAction(playing?SyncService.USER_PAUSE:SyncService.USER_RESUME));}
        else if(playing&&MediaBridge.supports(c,PlaybackState.ACTION_PAUSE))c.getTransportControls().pause();
        else if(!playing&&MediaBridge.supports(c,PlaybackState.ACTION_PLAY))c.getTransportControls().play();
    }
    /** The wrong automatic pick first offers the remembered playable alternatives; a plain
     *  rejection stays one tap away and still clears the cache, pin and history entry. */
    private void rejectMatch(){
        Track t=model.track;if(t==null)return;
        java.util.List<RecognitionClient.Candidate> known=recognition.cachedCandidates(t);
        if(!known.isEmpty()){chooseVersion(t,known);return;}
        model.update(L.t("Looking for other versions"),L.t("Searching the catalog for alternatives to choose from…"));
        final RequestScope scope=new RequestScope();lookupScope=scope;
        lookup.submit(()->{
            final java.util.List<RecognitionClient.Candidate> fetched=recognition.fetchCandidates(t,scope);
            runOnUiThread(()->{
                if(scope.isCancelled()||isFinishing()||model.track==null||!model.track.key.equals(t.key))return;
                if(fetched.isEmpty())sendReject();else chooseVersion(t,fetched);
            });
        });
    }
    private void sendReject(){startService(new Intent(this,SyncService.class).setAction(SyncService.REJECT));}
    private void chooseVersion(Track t,java.util.List<RecognitionClient.Candidate> candidates){
        String[] labels=new String[candidates.size()];
        for(int i=0;i<candidates.size();i++)labels[i]=candidates.get(i).title+" — "+candidates.get(i).artist;
        CatchWaveUi.dialog(this).setTitle(L.t("Which recording did you hear?"))
            .setItems(labels,(d,which)->applyChoice(t,candidates.get(which)))
            .setNegativeButton(L.t("None of these"),(d,w)->sendReject())
            .setNeutralButton(L.t("Cancel"),null).show();
    }
    private void applyChoice(Track t,RecognitionClient.Candidate chosen){
        MatchPins.put(this,t.key,chosen.url,chosen.title,chosen.artist);
        recognition.pin(t,chosen);
        MomentStore.get(this).replaceUrl(t.key,chosen.url);
        if(model.running){
            model.update(L.t("Recording choice saved"),L.t("Re-catching with the chosen version…"));
            startService(new Intent(this,SyncService.class).setAction(SyncService.USER_RESUME));
        }else model.update(L.t("Recording choice saved"),L.t("Press «Catch» near the music to continue the chosen version, or open it in YouTube Music."));
        render();
    }
    private void calibrateAudio(){
        MediaController c=new MediaBridge(this).controller();
        if(model.measuringAudio||!new CalibrationTarget(c,model.track).valid(c,model)){
            model.update(L.t("Catch a song first"),L.t("Refinement is available while the selected recording plays in single-track mode."));return;
        }
        model.update(L.t("Allow audio comparison"),L.t("Android will ask for capture permission. The app uses only YouTube Music's internal audio and the microphone; it does not record images."));
        MediaProjectionManager manager=getSystemService(MediaProjectionManager.class);
        Intent request=Build.VERSION.SDK_INT>=34?manager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()):manager.createScreenCaptureIntent();
        startActivityForResult(request,42);
    }
    @Override protected void onActivityResult(int code,int result,Intent data){
        super.onActivityResult(code,result,data);
        if(code!=42)return;
        if(result!=RESULT_OK||data==null){model.update(L.t("Refinement cancelled"),L.t("Internal audio permission not granted. Regular catching keeps working."));return;}
        try{startForegroundService(new Intent(this,AudioCalibrationService.class).setAction(AudioCalibrationService.START).putExtra("result",result).putExtra("consent",data));}
        catch(RuntimeException failure){model.update(L.t("Could not start refinement"),L.t("Open «CatchWave» and grant audio capture permission again."));}
    }
    private void renderPlayer(){
        renderPlayer(new MediaBridge(this).controller());
    }
    private void renderPlayer(MediaController c){
        if(playerPanel==null)return;PlaybackState p=c==null?null:c.getPlaybackState();MediaMetadata m=c==null?null:c.getMetadata();
        playerPanel.setVisibility(p==null?View.GONE:View.VISIBLE);if(p==null)return;
        String title=m==null?null:m.getString(MediaMetadata.METADATA_KEY_TITLE),artist=m==null?null:m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        playerTitle.setText("YouTube Music · "+(title==null?"":title)+"\n"+(artist==null?"":artist));
        boolean playing=p.getState()==PlaybackState.STATE_PLAYING;long duration=m==null?0:m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        boolean failed=p.getState()==PlaybackState.STATE_ERROR;
        long position=SyncMath.playerPosition(p.getPosition(),p.getLastPositionUpdateTime(),p.getPlaybackSpeed(),SystemClock.elapsedRealtime(),playing);
        playerClock.setText((failed?L.t("Playback unavailable"):timeLabel(position)+" / "+timeLabel(duration))+(model.manualHold?L.t(" · manual control"):"")+(p.getState()==PlaybackState.STATE_BUFFERING||p.getState()==PlaybackState.STATE_CONNECTING?L.t(" · loading"):""));
        playerFailure.setVisibility(failed?View.VISIBLE:View.GONE);retryPlayer.setVisibility(failed?View.VISIBLE:View.GONE);
        if(failed)playerFailure.setText("YouTube Music: "+(p.getErrorMessage()==null?L.t("could not start the recording"):p.getErrorMessage())+L.t("\nCatch again. If the recording is unavailable, choose another version in the player."));
        playPause.setText(playing?L.t("Pause"):L.t("Start"));playPause.setContentDescription(playing?L.t("Pause"):L.t("Playback"));playPause.setEnabled(!model.measuringAudio&&!failed&&MediaBridge.supports(c,playing?PlaybackState.ACTION_PAUSE:PlaybackState.ACTION_PLAY));
        timeline.setEnabled(!model.measuringAudio&&!failed&&duration>0&&MediaBridge.supports(c,PlaybackState.ACTION_SEEK_TO));
        if(!dragging)timeline.setProgress(duration<=0?0:(int)Math.min(1000,Math.max(0,position)*1000/duration));
    }
    private static String timeLabel(long ms){if(ms<0)return "—";long seconds=ms/1000;return String.format(Locale.ROOT,"%d:%02d",seconds/60,seconds%60);}
    private void permissionsInfo(){CatchWaveUi.dialog(this).setTitle(L.t("Ready to catch"))
        .setMessage(L.t("Microphone — recognize music nearby.\nPlayer — read the position, seek and pause only in YouTube Music.\nHeadphones — required for Live Sync.\n\nOn OnePlus, if it stops in the background, check the app's battery settings and allow background activity."))
        .setPositiveButton(L.t("Player access"),(d,w)->openNotificationSettings()).setNegativeButton(L.t("Close"),null).show();}
    private void settings(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(24),dp(10),dp(24),dp(10));
        TextView label=text("",16,TEXT);box.addView(label);
        TextView hint=text(L.t("A positive correction seeks forward. Tune it by ear for your headphones."),13,MUTED);add(box,hint,8);
        SeekBar seek=new SeekBar(this);seek.setContentDescription(L.t("Manual time correction"));seek.setMax(40);seek.setProgress(getPreferences().getInt("adjustment",0)/100+20);
        label.setText(L.t("Correction: ")+getPreferences().getInt("adjustment",0)+L.t(" ms"));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){if(model.running)startService(new Intent(MainActivity.this,SyncService.class).setAction(SyncService.ADJUST));}public void onProgressChanged(SeekBar b,int p,boolean user){int value=(p-20)*100;label.setText(L.t("Correction: ")+value+L.t(" ms"));if(user)getPreferences().edit().putInt("adjustment",value).apply();}});add(box,seek,12);
        Button access=button(L.t("Player access"),PANEL,TEXT);access.setOnClickListener(v->openNotificationSettings());add(box,access,12);
        CheckBox compatible=new CheckBox(this);compatible.setText(L.t("Allow opening YouTube Music on failure"));compatible.setTextColor(TEXT);compatible.setChecked(getPreferences().getBoolean("compatibleLaunch",false));
        compatible.setOnCheckedChangeListener((b,checked)->getPreferences().edit().putBoolean("compatibleLaunch",checked).apply());add(box,compatible,10);
        add(box,text(L.t("If background launch fails, opens the found recording and asks to return here after setting the timecode. Launching from the background requires opening «CatchWave»."),12,MUTED),4);
        Button app=button(L.t("Android settings"),PANEL,TEXT);app.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))));add(box,app,8);
        Button data=button(Privacy.title(),PANEL,TEXT);data.setOnClickListener(v->privacy());add(box,data,8);
        Button diag=button(L.t("Diagnostics"),PANEL,TEXT);diag.setOnClickListener(v->diagnostics());add(box,diag,8);
        Button about=button(L.t("About and licenses"),PANEL,TEXT);about.setOnClickListener(v->about());add(box,about,8);
        ScrollView settingsScroll=new ScrollView(this);settingsScroll.addView(box);
        CatchWaveUi.dialog(this).setTitle(L.t("Catch settings")).setView(settingsScroll).setPositiveButton(L.t("Done"),null).show();
    }
    private void diagnostics(){
        android.media.session.MediaController c=new MediaBridge(this).controller();
        android.media.session.PlaybackState p=c==null?null:c.getPlaybackState();
        String report=AppIdentity.label()+"\nAndroid "+Build.VERSION.RELEASE+" · "+Build.MANUFACTURER+" "+Build.MODEL
            +L.t("\nAdvertising ID: not requested\nAnalytics: none\nShazam app: not required\nYouTube Music (player): ")+youtubeInstalled()+L.t("\nPlayer access: ")+MediaBridge.allowed(this)+L.t("\nMedia session: ")+(c!=null)
            +L.t("\nSeeking: ")+MediaBridge.supports(c,android.media.session.PlaybackState.ACTION_SEEK_TO)
            +"\n"+MediaBridge.outputLabel(this)+L.t("\nPlayer state: ")+(p==null?"—":p.getState())
            +"\n"+new MediaBridge(this).diagnosticSnapshot()+"\n"+model.report();
        CatchWaveUi.dialog(this).setTitle(L.t("Diagnostics")).setMessage(report).setPositiveButton(L.t("Close"),null)
            .setNeutralButton(L.t("Share"),(d,w)->startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,report),L.t("Share diagnostics")))).show();
    }
    private void about(){
        CatchWaveUi.dialog(this).setTitle(L.t("Catch / ")+AppIdentity.label())
            .setMessage(L.t("Experimental catching into YouTube Music. Audio processing is a Java adaptation of SongRec / Audile on this phone. The Shazam app is not required. Fingerprint catalog over HTTPS; player via the native YouTube Music media session, with public web search as a fallback.\n\nRecordings are not saved. There is no advertising ID, analytics or built-in ads. The catalog receives the fingerprint; YouTube Music receives the title and artist if chosen as the player.\n\nSync is confirmed by the player timecode; microsecond accuracy is not guaranteed. Different editions and account restrictions can interfere with catching.\n\nSource code is distributed with the APK under GPL-3.0-or-later."))
            .setPositiveButton(L.t("Close"),null).setNeutralButton("GPL",(d,w)->license()).setNegativeButton(L.t("Data"),(d,w)->privacy()).show();
    }
    private void privacy(){
        CatchWaveUi.dialog(this).setTitle(Privacy.title()).setMessage(Privacy.notice()).setPositiveButton(L.t("Close"),null).show();
    }
    static int licenseStart(String license){
        if(license==null)return 0;
        int terms=license.indexOf("TERMS AND CONDITIONS");
        return Math.max(0,terms);
    }
    private void license(){
        try(InputStream in=getResources().openRawResource(R.raw.license);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
            String license=out.toString("UTF-8");
            TextView text=text(license,12,TEXT);text.setPadding(dp(16),dp(16),dp(16),dp(16));text.setFocusable(false);
            ScrollView scroll=new ScrollView(this);scroll.addView(text);
            CatchWaveUi.dialog(this).setTitle("GNU GPL v3").setView(scroll).setPositiveButton(L.t("Close"),null).show();
            int start=licenseStart(license);
            scroll.post(()->{
                scroll.scrollTo(0,0);
                if(text.getLayout()!=null&&start>0){
                    int line=text.getLayout().getLineForOffset(start);
                    scroll.scrollTo(0,text.getLayout().getLineTop(line));
                }
            });
        }catch(IOException ignored){}
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){
        super.onRequestPermissionsResult(code,permissions,results);
        if(code==10&&(results.length==0||results[0]!=PackageManager.PERMISSION_GRANTED)){pendingStart=false;model.update(L.t("Microphone required"),L.t("Allow audio recording to recognize music."));return;}
        if(pendingStart)begin();
    }
    @Override protected void onResume(){super.onResume();visible=true;LanAudioService.uiVisible=true;LanAudioService.maybeAutoStart(this);if(LanState.INSTANCE.started)startService(new Intent(this,LanAudioService.class).setAction(LanAudioService.REFRESH));if(playerLaunch!=null)playerLaunch.cancel();render();playerHandler.removeCallbacks(playerTick);playerHandler.post(playerTick);}
    @Override protected void onPause(){visible=false;LanAudioService.uiVisible=false;if(LanState.INSTANCE.started)startService(new Intent(this,LanAudioService.class).setAction(LanAudioService.REFRESH));playerHandler.removeCallbacks(playerTick);super.onPause();}
    @Override protected void onDestroy(){
        if(playerLaunch!=null)playerLaunch.cancel();model.remove(listener);
        RequestScope scope=lookupScope;if(scope!=null)scope.cancel();lookup.shutdownNow();
        super.onDestroy();
    }
    @Override public void dump(String prefix,FileDescriptor fd,PrintWriter writer,String[] args){
        super.dump(prefix,fd,writer,args);writer.println(model.report());writer.println(new MediaBridge(this).diagnosticSnapshot());
    }
    private TextView text(String value,float size,int color){return CatchWaveUi.text(this,value,size,color,false);}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private Button button(String text,int bg,int fg){return CatchWaveUi.button(this,text,bg==ACCENT);}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private void add(LinearLayout parent,View v,int margin){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(margin);parent.addView(v,p);}
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    final class WaveView extends LinearLayout {
        double level;boolean listening,aligned;private final ProgressBar meter;private final TextView caption;
        WaveView(Context c){
            super(c);setOrientation(VERTICAL);setGravity(Gravity.CENTER);setPadding(dp(16),dp(16),dp(16),dp(16));
            ImageView icon=new ImageView(c);icon.setImageResource(R.drawable.ic_lan_graphic_eq);icon.setImageTintList(android.content.res.ColorStateList.valueOf(ACCENT));icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);addView(icon,new LinearLayout.LayoutParams(dp(38),dp(38)));
            caption=text("",12,MUTED);caption.setGravity(Gravity.CENTER);add(this,caption,8);
            meter=new ProgressBar(c,null,android.R.attr.progressBarStyleHorizontal);meter.setMax(100);meter.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));add(this,meter,8);setContentDescription(L.t("Microphone level"));
        }
        @Override public void invalidate(){
            if(meter!=null){meter.setProgress(listening?(int)Math.min(100,Math.max(0,level)*1400):0);caption.setText(listening?L.t("Listening to the microphone"):aligned?L.t("Song moment found"):L.t("The microphone turns on after start"));}
            super.invalidate();
        }
    }
}
