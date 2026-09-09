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
    private static final int BG=Color.rgb(16,21,16),PANEL=Color.rgb(29,36,29),TEXT=Color.rgb(238,245,230),MUTED=Color.rgb(164,177,159),ACCENT=Color.rgb(185,246,107);
    private final SessionModel model=SessionModel.INSTANCE;
    private final Runnable listener=this::render;
    private TextView status,detail,track,delta,setup,modeNote;
    private Button start,open,reject,calibrate,sync;
    private Switch live;
    private WaveView wave;
    private ProgressBar progress;
    private boolean pendingStart,refinePrompted;
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
    @Override public void onCreate(Bundle state){
        super.onCreate(state);playerLaunch=new PlayerLaunch(this,model);buildUi();model.add(listener);render();
    }
    private void buildUi(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(24),dp(16),dp(24),dp(26));
        scroll.addView(body);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{
            v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;
        });
        LinearLayout header=row();TextView brand=text("ПОДХВАТ",15,ACCENT);brand.setLetterSpacing(.19f);brand.setTypeface(null,Typeface.BOLD);
        header.addView(brand,new LinearLayout.LayoutParams(0,dp(48),1));brand.setGravity(Gravity.CENTER_VERTICAL);
        Button settings=button("Настройки",PANEL,TEXT);settings.setTextSize(12);settings.setOnClickListener(v->settings());header.addView(settings,new LinearLayout.LayoutParams(dp(118),dp(44)));body.addView(header);
        TextView heading=text("Музыка вокруг.\nТеперь у тебя.",34,TEXT);heading.setTypeface(null,Typeface.BOLD);heading.setLineSpacing(dp(1),1);add(body,heading,20);
        TextView intro=text("Подхвати текущий момент\nв YouTube Music",15,MUTED);intro.setLineSpacing(dp(3),1);add(body,intro,10);
        wave=new WaveView(this);LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(-1,dp(120));wp.topMargin=dp(8);body.addView(wave,wp);
        status=text("",20,TEXT);status.setTypeface(null,Typeface.BOLD);status.setGravity(Gravity.CENTER);add(body,status,0);
        detail=text("",14,MUTED);detail.setGravity(Gravity.CENTER);detail.setLineSpacing(dp(3),1);add(body,detail,8);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,dp(3));pp.topMargin=dp(18);body.addView(progress,pp);
        track=text("",16,TEXT);track.setPadding(dp(16),dp(14),dp(16),dp(14));track.setBackground(shape(PANEL,16));add(body,track,18);
        delta=text("",12,MUTED);delta.setGravity(Gravity.CENTER);add(body,delta,6);
        buildPlayer(body);
        calibrate=button("Уточнить по звуку",PANEL,ACCENT);calibrate.setOnClickListener(v->calibrateAudio());add(body,calibrate,12);
        start=button("Подхватить",ACCENT,BG);start.setTextSize(18);start.setTypeface(null,Typeface.BOLD);start.setOnClickListener(v->{if(model.measuringAudio)stopService(new Intent(this,AudioCalibrationService.class));else if(model.running)stop();else begin();});
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,dp(60));bp.topMargin=dp(18);body.addView(start,bp);
        LinearLayout mode=new LinearLayout(this);mode.setOrientation(LinearLayout.VERTICAL);mode.setPadding(dp(16),dp(10),dp(16),dp(14));mode.setBackground(shape(PANEL,18));
        live=new Switch(this);live.setText("Live Sync");live.setTextColor(TEXT);live.setTextSize(17);live.setPadding(0,dp(6),0,dp(6));
        live.setChecked(getPreferences().getBoolean("live",false));live.setOnCheckedChangeListener((b,checked)->{getPreferences().edit().putBoolean("live",checked).apply();modeNote.setText(checked?"Следить за паузой и сменой песни. Нужны наушники.":"Один подхват — дальше сам");});mode.addView(live);
        modeNote=text(live.isChecked()?"Следить за паузой и сменой песни. Нужны наушники.":"Один подхват — дальше сам",13,MUTED);modeNote.setLineSpacing(dp(2),1);mode.addView(modeNote);add(body,mode,16);
        open=button("Открыть YouTube Music",PANEL,TEXT);open.setOnClickListener(v->openTrack());add(body,open,10);
        reject=button("Это другая версия",PANEL,MUTED);reject.setOnClickListener(v->startService(new Intent(this,SyncService.class).setAction(SyncService.REJECT)));add(body,reject,8);
        setup=text("",12,MUTED);setup.setGravity(Gravity.CENTER);setup.setPadding(0,dp(8),0,0);setup.setOnClickListener(v->permissionsInfo());add(body,setup,8);
        TextView footer=text(AppIdentity.label()+" · экспериментальная версия",11,MUTED);footer.setGravity(Gravity.CENTER);add(body,footer,18);
    }
    private android.content.SharedPreferences getPreferences(){return getSharedPreferences("settings",MODE_PRIVATE);}
    private void begin(){
        pendingStart=true;
        if(!getPreferences().getBoolean("intro",false)){
            new AlertDialog.Builder(this).setTitle("Как работает подхват")
                .setMessage(RecognitionPath.explanation()+"\n\nСама запись остаётся в оперативной памяти и не сохраняется. Рекламного идентификатора, аналитики и встроенной рекламы нет.\n\nДля перемотки нужен доступ к медиасессии через разрешение «Доступ к уведомлениям». Содержимое уведомлений приложение не читает.\n\nLive Sync пока работает только с наушниками.")
                .setPositiveButton("Продолжить",(d,w)->{getPreferences().edit().putBoolean("intro",true).apply();begin();})
                .setNegativeButton("Позже",(d,w)->pendingStart=false).show();return;
        }
        if(!youtubeInstalled()){pendingStart=false;new AlertDialog.Builder(this).setTitle("Нужен YouTube Music").setMessage("Установите YouTube Music и войдите в свой аккаунт, затем вернитесь в «Подхват».").setPositiveButton("Понятно",null).show();return;}
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},10);return;}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED&&!getPreferences().getBoolean("notificationAsked",false)){
            getPreferences().edit().putBoolean("notificationAsked",true).apply();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},11);return;
        }
        if(!MediaBridge.allowed(this)){
            pendingStart=false;new AlertDialog.Builder(this).setTitle("Разреши управление плеером")
                .setMessage("В следующем окне включи «Подхват». Это даст приложению доступ к медиасессии YouTube Music для чтения позиции, перемотки и паузы. Затем вернись и нажми «Подхватить».")
                .setPositiveButton("Открыть настройки",(d,w)->openNotificationSettings()).setNegativeButton("Позже",null).show();return;
        }
        if(live.isChecked()&&!MediaBridge.headphones(this)){pendingStart=false;new AlertDialog.Builder(this).setTitle("Для Live Sync нужны наушники").setMessage(MediaBridge.outputLabel(this)+".\n\nВыбери наушники для воспроизведения музыки. Через динамик доступен обычный подхват: выключи Live Sync. Непрерывное сопровождение через динамик пока не отделяет свою музыку от внешней.").setPositiveButton("Понятно",null).show();return;}
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
        }catch(ActivityNotFoundException|SecurityException e){if(model.running)startService(new Intent(this,SyncService.class).setAction(SyncService.OPEN_FAILED));else model.update("YouTube Music недоступен","Установите приложение и повторите подхват.");}
    }
    private void render(){
        if(status==null||isFinishing())return;
        status.setText(model.status);detail.setText(model.detail);start.setText(model.measuringAudio?"Отменить уточнение":model.running?"Остановить сеанс":"Подхватить");live.setEnabled(!model.running);
        calibrate.setVisibility(model.guardingTrack&&!model.live?View.VISIBLE:View.GONE);calibrate.setEnabled(!model.measuringAudio);
        sync.setEnabled(!model.measuringAudio);open.setEnabled(!model.measuringAudio);reject.setEnabled(!model.measuringAudio);
        if(model.measuringAudio||model.running&&!model.guardingTrack)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        progress.setVisibility(model.running&&model.track==null?View.VISIBLE:View.INVISIBLE);progress.setProgress(model.progress);
        wave.level=model.level;wave.listening=model.running;wave.aligned=model.aligned;wave.invalidate();
        Track current=model.track;track.setVisibility(current==null?View.GONE:View.VISIBLE);delta.setVisibility(current==null?View.GONE:View.VISIBLE);open.setVisibility(current==null?View.GONE:View.VISIBLE);
        reject.setVisibility(current==null?View.GONE:View.VISIBLE);
        if(current!=null){
            track.setText(current.title+"\n"+current.artist);
            delta.setText(model.errorMs==Long.MAX_VALUE?"Ожидаю позицию плеера":String.format(Locale.ROOT,"Δ таймкодов плеера: %+.0f мс%s\nЭто не измерение акустической задержки. 3×≤120 мс — согласованность распознавателя.",(double)model.errorMs,model.seekLagMs==Long.MIN_VALUE?"":String.format(Locale.ROOT," · seek_lag %d мс",model.seekLagMs)));
            if(Double.isFinite(model.audioLagMs))delta.setText(String.format(Locale.ROOT,"Внутренний звук к микрофону: %+.1f мс\nЗадержка динамика не измерена.",model.audioLagMs));
        }
        setup.setText((checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED?"●":"○")+" Микрофон    "+(MediaBridge.allowed(this)?"●":"○")+" Плеер\n"+MediaBridge.outputLabel(this));
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
        timeline=new SeekBar(this);timeline.setContentDescription("Позиция песни");timeline.setMax(1000);timeline.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        timeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar bar){dragging=true;if(model.running){model.manualHold=true;startService(new Intent(MainActivity.this,SyncService.class).setAction(SyncService.USER_HOLD));}}
            public void onProgressChanged(SeekBar bar,int value,boolean user){}
            public void onStopTrackingTouch(SeekBar bar){
                MediaController c=new MediaBridge(MainActivity.this).controller();MediaMetadata m=c==null?null:c.getMetadata();long duration=m==null?0:m.getLong(MediaMetadata.METADATA_KEY_DURATION);
                if(duration>0&&MediaBridge.supports(c,PlaybackState.ACTION_SEEK_TO))c.getTransportControls().seekTo(duration*bar.getProgress()/1000);
                dragging=false;
            }
        });add(playerPanel,timeline,4);
        LinearLayout buttons=row();playPause=button("▶",ACCENT,BG);playPause.setContentDescription("Воспроизведение или пауза");playPause.setOnClickListener(v->togglePlayback());
        buttons.addView(playPause,new LinearLayout.LayoutParams(dp(70),dp(46)));
        sync=button("Синхронизировать",PANEL,ACCENT);sync.setTextSize(14);sync.setOnClickListener(v->{if(model.running)startService(new Intent(this,SyncService.class).setAction(SyncService.USER_RESUME));else begin();});
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,dp(46),1);sp.leftMargin=dp(8);buttons.addView(sync,sp);add(playerPanel,buttons,4);
        playerFailure=text("",13,TEXT);add(playerPanel,playerFailure,8);
        retryPlayer=button("Повторить подхват",PANEL,ACCENT);retryPlayer.setOnClickListener(v->{if(model.running)startService(new Intent(this,SyncService.class).setAction(SyncService.USER_RESUME));else begin();});add(playerPanel,retryPlayer,6);
        add(parent,playerPanel,12);
    }
    private void togglePlayback(){
        MediaController c=new MediaBridge(this).controller();PlaybackState p=c==null?null:c.getPlaybackState();if(p==null)return;
        boolean playing=p.getState()==PlaybackState.STATE_PLAYING;
        if(model.running){if(playing)model.manualHold=true;startService(new Intent(this,SyncService.class).setAction(playing?SyncService.USER_PAUSE:SyncService.USER_RESUME));}
        else if(playing&&MediaBridge.supports(c,PlaybackState.ACTION_PAUSE))c.getTransportControls().pause();
        else if(!playing&&MediaBridge.supports(c,PlaybackState.ACTION_PLAY))c.getTransportControls().play();
    }
    private void calibrateAudio(){
        MediaController c=new MediaBridge(this).controller();
        if(model.measuringAudio||!new CalibrationTarget(c,model.track).valid(c,model)){
            model.update("Сначала подхвати песню","Уточнение доступно, пока выбранная запись играет в режиме одного трека.");return;
        }
        model.update("Разреши сравнение звука","Android запросит захват. Приложение использует только внутренний звук YouTube Music и микрофон; изображения не записывает.");
        MediaProjectionManager manager=getSystemService(MediaProjectionManager.class);
        Intent request=Build.VERSION.SDK_INT>=34?manager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()):manager.createScreenCaptureIntent();
        startActivityForResult(request,42);
    }
    @Override protected void onActivityResult(int code,int result,Intent data){
        super.onActivityResult(code,result,data);
        if(code!=42)return;
        if(result!=RESULT_OK||data==null){model.update("Уточнение отменено","Разрешение на внутренний звук не получено. Обычный подхват продолжает работать.");return;}
        try{startForegroundService(new Intent(this,AudioCalibrationService.class).setAction(AudioCalibrationService.START).putExtra("result",result).putExtra("consent",data));}
        catch(RuntimeException failure){model.update("Не удалось начать уточнение","Открой «Подхват» и повтори разрешение на захват звука.");}
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
        playerClock.setText((failed?"Воспроизведение недоступно":timeLabel(position)+" / "+timeLabel(duration))+(model.manualHold?" · ручное управление":"")+(p.getState()==PlaybackState.STATE_BUFFERING||p.getState()==PlaybackState.STATE_CONNECTING?" · загрузка":""));
        playerFailure.setVisibility(failed?View.VISIBLE:View.GONE);retryPlayer.setVisibility(failed?View.VISIBLE:View.GONE);
        if(failed)playerFailure.setText("YouTube Music: "+(p.getErrorMessage()==null?"не удалось запустить запись":p.getErrorMessage())+"\nПовтори подхват. Если запись недоступна, выбери другую версию в плеере.");
        playPause.setText(playing?"❚❚":"▶");playPause.setContentDescription(playing?"Пауза":"Воспроизведение");playPause.setEnabled(!model.measuringAudio&&!failed&&MediaBridge.supports(c,playing?PlaybackState.ACTION_PAUSE:PlaybackState.ACTION_PLAY));
        timeline.setEnabled(!model.measuringAudio&&!failed&&duration>0&&MediaBridge.supports(c,PlaybackState.ACTION_SEEK_TO));
        if(!dragging)timeline.setProgress(duration<=0?0:(int)Math.min(1000,Math.max(0,position)*1000/duration));
    }
    private static String timeLabel(long ms){if(ms<0)return "—";long seconds=ms/1000;return String.format(Locale.ROOT,"%d:%02d",seconds/60,seconds%60);}
    private void permissionsInfo(){new AlertDialog.Builder(this).setTitle("Готовность к подхвату")
        .setMessage("Микрофон — распознавать музыку рядом.\nПлеер — читать позицию, перематывать и ставить паузу только в YouTube Music.\nНаушники — необходимы для Live Sync.\n\nНа OnePlus при остановках в фоне проверь настройки батареи приложения и разреши фоновую работу.")
        .setPositiveButton("Доступ к плееру",(d,w)->openNotificationSettings()).setNegativeButton("Закрыть",null).show();}
    private void settings(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(24),dp(10),dp(24),dp(10));
        TextView label=text("",16,TEXT);box.addView(label);
        TextView hint=text("Положительная поправка перематывает вперёд. Настраивай на слух для своих наушников.",13,MUTED);add(box,hint,8);
        SeekBar seek=new SeekBar(this);seek.setContentDescription("Ручная поправка времени");seek.setMax(40);seek.setProgress(getPreferences().getInt("adjustment",0)/100+20);
        label.setText("Поправка: "+getPreferences().getInt("adjustment",0)+" мс");
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){if(model.running)startService(new Intent(MainActivity.this,SyncService.class).setAction(SyncService.ADJUST));}public void onProgressChanged(SeekBar b,int p,boolean user){int value=(p-20)*100;label.setText("Поправка: "+value+" мс");if(user)getPreferences().edit().putInt("adjustment",value).apply();}});add(box,seek,12);
        Button access=button("Доступ к плееру",PANEL,TEXT);access.setOnClickListener(v->openNotificationSettings());add(box,access,12);
        CheckBox compatible=new CheckBox(this);compatible.setText("Разрешить открытие YouTube Music при сбое");compatible.setTextColor(TEXT);compatible.setChecked(getPreferences().getBoolean("compatibleLaunch",false));
        compatible.setOnCheckedChangeListener((b,checked)->getPreferences().edit().putBoolean("compatibleLaunch",checked).apply());add(box,compatible,10);
        add(box,text("Если фоновый запуск не сработал, откроет найденную запись и запросит возврат сюда после установки таймкода. Для запуска из фона понадобится открыть «Подхват».",12,MUTED),4);
        Button app=button("Настройки Android",PANEL,TEXT);app.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))));add(box,app,8);
        Button data=button(Privacy.TITLE,PANEL,TEXT);data.setOnClickListener(v->privacy());add(box,data,8);
        Button diag=button("Диагностика",PANEL,TEXT);diag.setOnClickListener(v->diagnostics());add(box,diag,8);
        Button about=button("О приложении и лицензии",PANEL,TEXT);about.setOnClickListener(v->about());add(box,about,8);
        ScrollView settingsScroll=new ScrollView(this);settingsScroll.addView(box);
        new AlertDialog.Builder(this).setTitle("Настройки подхвата").setView(settingsScroll).setPositiveButton("Готово",null).show();
    }
    private void diagnostics(){
        android.media.session.MediaController c=new MediaBridge(this).controller();
        android.media.session.PlaybackState p=c==null?null:c.getPlaybackState();
        String report=AppIdentity.label()+"\nAndroid "+Build.VERSION.RELEASE+" · "+Build.MANUFACTURER+" "+Build.MODEL
            +"\nРекламный ID: не запрашивается\nАналитика: нет\nПриложение Shazam: не требуется\nYouTube Music (плеер): "+youtubeInstalled()+"\nДоступ к плееру: "+MediaBridge.allowed(this)+"\nМедиасессия: "+(c!=null)
            +"\nПеремотка: "+MediaBridge.supports(c,android.media.session.PlaybackState.ACTION_SEEK_TO)
            +"\n"+MediaBridge.outputLabel(this)+"\nСостояние плеера: "+(p==null?"—":p.getState())
            +"\n"+new MediaBridge(this).diagnosticSnapshot()+"\n"+model.report();
        new AlertDialog.Builder(this).setTitle("Диагностика").setMessage(report).setPositiveButton("Закрыть",null)
            .setNeutralButton("Поделиться",(d,w)->startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,report),"Поделиться диагностикой"))).show();
    }
    private void about(){
        new AlertDialog.Builder(this).setTitle("Подхват / "+AppIdentity.label())
            .setMessage("Экспериментальный подхват в YouTube Music. Обработка звука — Java-адаптация SongRec / Audile на этом телефоне. Приложение Shazam не требуется. Каталог отпечатков — HTTPS; плеер — нативная медиасессия YouTube Music, резерв — публичный веб-поиск.\n\nЗаписи не сохраняются. Рекламного идентификатора, аналитики и встроенной рекламы нет. Каталог получает отпечаток; YouTube Music — название и исполнителя, если выбран как плеер.\n\nСинхронизация подтверждается таймкодом плеера; микросекундная точность не гарантируется. Разные издания и ограничения аккаунта могут мешать подхвату.\n\nИсходники распространяются с APK под GPL-3.0-or-later.")
            .setPositiveButton("Закрыть",null).setNeutralButton("GPL",(d,w)->license()).setNegativeButton("Данные",(d,w)->privacy()).show();
    }
    private void privacy(){
        new AlertDialog.Builder(this).setTitle(Privacy.TITLE).setMessage(Privacy.notice()).setPositiveButton("Закрыть",null).show();
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
            new AlertDialog.Builder(this).setTitle("GNU GPL v3").setView(scroll).setPositiveButton("Закрыть",null).show();
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
        if(code==10&&(results.length==0||results[0]!=PackageManager.PERMISSION_GRANTED)){pendingStart=false;model.update("Нужен микрофон","Разрешите запись звука, чтобы распознавать музыку.");return;}
        if(pendingStart)begin();
    }
    @Override protected void onResume(){super.onResume();visible=true;if(playerLaunch!=null)playerLaunch.cancel();render();playerHandler.removeCallbacks(playerTick);playerHandler.post(playerTick);}
    @Override protected void onPause(){visible=false;playerHandler.removeCallbacks(playerTick);super.onPause();}
    @Override protected void onDestroy(){if(playerLaunch!=null)playerLaunch.cancel();model.remove(listener);super.onDestroy();}
    @Override public void dump(String prefix,FileDescriptor fd,PrintWriter writer,String[] args){
        super.dump(prefix,fd,writer,args);writer.println(model.report());writer.println(new MediaBridge(this).diagnosticSnapshot());
    }
    private TextView text(String value,float size,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);return v;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private Button button(String text,int bg,int fg){Button b=new Button(this);b.setText(text);b.setTextColor(fg);b.setAllCaps(false);b.setBackground(shape(bg,18));b.setPadding(dp(14),0,dp(14),0);b.setMinHeight(dp(46));return b;}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private void add(LinearLayout parent,View v,int margin){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(margin);parent.addView(v,p);}
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    final class WaveView extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);double level;boolean listening,aligned;
        WaveView(Context c){super(c);setContentDescription("Индикатор уровня микрофона");}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);float x=getWidth()/2f,y=getHeight()/2f,r=dp(76);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));
            for(int i=0;i<3;i++){paint.setColor(i==0?Color.rgb(58,78,48):Color.rgb(35,47,32));canvas.drawCircle(x,y,r+dp(i*17),paint);}
            paint.setStyle(Paint.Style.FILL);paint.setColor(PANEL);canvas.drawCircle(x,y,r,paint);
            paint.setStrokeWidth(dp(7));paint.setStrokeCap(Paint.Cap.ROUND);paint.setColor(ACCENT);
            float activity=listening?(float)Math.min(1,level*14):.35f;
            for(int i=-3;i<=3;i++){float height=dp((float)(13+(1-Math.abs(i)/4f)*37)*(0.55f+activity));canvas.drawLine(x+dp(i*14),y-height/2,x+dp(i*14),y+height/2,paint);}
            if(aligned){paint.setStyle(Paint.Style.FILL);paint.setColor(ACCENT);canvas.drawCircle(x+dp(56),y+dp(56),dp(13),paint);paint.setColor(BG);paint.setStrokeWidth(dp(2));canvas.drawLine(x+dp(50),y+dp(56),x+dp(54),y+dp(60),paint);canvas.drawLine(x+dp(54),y+dp(60),x+dp(63),y+dp(51),paint);}
        }
    }
}
