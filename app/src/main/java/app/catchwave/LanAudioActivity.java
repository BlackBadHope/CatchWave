// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.widget.*;

public final class LanAudioActivity extends Activity {
    private final LanState state=LanState.INSTANCE;private final Runnable listener=this::render;
    private LanSettings settings;private TextView status,detail,advanced,sensors,controlStatus,statistics,action;private Button connect,scan,control;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);settings=new LanSettings(this);state.initialize(settings);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(24,16,24,0);setContentView(root);
        root.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets safe=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());
            view.setPadding(24+safe.left,16+safe.top,24+safe.right,safe.bottom);return insets;
        });root.requestApplyInsets();
        TextView title=new TextView(this);title.setText("Звук с ПК");title.setTextSize(28);root.addView(title);
        status=new TextView(this);status.setTextSize(20);root.addView(status);detail=new TextView(this);root.addView(detail);
        ScrollView scroll=new ScrollView(this);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(8,16,8,32);scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        statistics=new TextView(this);body.addView(statistics);sensors=new TextView(this);body.addView(sensors);
        controlStatus=new TextView(this);body.addView(controlStatus);action=new TextView(this);body.addView(action);
        scan=new Button(this);scan.setText("Сканировать QR");scan.setOnClickListener(v->{if(!state.started)startActivityForResult(new Intent(this,PairingScanActivity.class),42);});body.addView(scan);
        connect=new Button(this);connect.setOnClickListener(v->{if(state.started)stop();else begin();});body.addView(connect);
        control=new Button(this);control.setOnClickListener(v->{
            if(effectiveControl()){settings.hostControl(false);refresh();}
            else if(state.connected&&state.hostAllowed)new AlertDialog.Builder(this).setTitle("Включить управление хостом?")
                .setMessage("Жесты play/pause, next и previous будут управлять проигрывателем на вашем ПК.")
                .setPositiveButton("Включить",(d,w)->{settings.hostControl(true);refresh();}).setNegativeButton("Отмена",null).show();
        });body.addView(control);
        Switch gestures=new Switch(this);gestures.setText("Разрешить жесты");gestures.setChecked(settings.gestures());body.addView(gestures);
        gestures.setOnCheckedChangeListener((button,value)->{settings.prefs.edit().putBoolean("gestures",value).apply();refresh();});
        TextView guide=new TextView(this);guide.setText("После подключения держите телефон спокойно 1,5 секунды для нейтрали. Медленно наклоните верх телефона вправо/влево примерно на 30° и удерживайте: громкость телефона меняется шагами каждые 0,4 с. Верните в нейтраль, чтобы остановить. Встряхивание или 3 постукивания: play/pause; 4: next; 5: previous; 6: mute/restore телефона. Для команд ПК и регулировки наклоном включите управление на обеих сторонах. Выключение управления сразу прекращает регулировку наклоном.\nПостукивайте легко по корпусу с промежутками 0,2–0,5 с; после серии сделайте паузу 0,7 с. Next/previous зависят от поддержки плеера на ПК.\nЖесты экспериментальны; надёжность и слышимый звук ещё проверяются на устройстве.");body.addView(guide);
        Button neutral=new Button(this);neutral.setText("Обновить нейтраль");neutral.setOnClickListener(v->{if(state.started)startService(new Intent(this,LanAudioService.class).setAction(LanAudioService.CALIBRATE));});body.addView(neutral);
        LinearLayout extras=new LinearLayout(this);extras.setOrientation(LinearLayout.VERTICAL);extras.setVisibility(android.view.View.GONE);
        Button more=new Button(this);more.setText("Дополнительно");more.setOnClickListener(v->extras.setVisibility(extras.getVisibility()==android.view.View.GONE?android.view.View.VISIBLE:android.view.View.GONE));body.addView(more);body.addView(extras);
        advanced=new TextView(this);extras.addView(advanced);
        Switch off=new Switch(this);off.setText("Разрешить жесты при выключенном экране");off.setChecked(settings.screenOff());extras.addView(off);
        off.setOnCheckedChangeListener((button,value)->{settings.prefs.edit().putBoolean("screenOff",value).apply();refresh();});
        TextView offGuide=new TextView(this);offGuide.setText("По умолчанию жесты работают, пока CatchWave виден. Режим с выключенным экраном и расход батареи на OnePlus ещё не проверены.");extras.addView(offGuide);
        Button forget=new Button(this);forget.setText("Забыть сопряжение");forget.setOnClickListener(v->{stop();settings.forget();state.reset(settings);render();});extras.addView(forget);
        Button back=new Button(this);back.setText("Вернуться");back.setOnClickListener(v->finish());body.addView(back);
        state.listeners.add(listener);render();
    }
    private void begin(){
        if(!settings.paired()){startActivityForResult(new Intent(this,PairingScanActivity.class),42);return;}
        if(SessionModel.INSTANCE.running||SessionModel.INSTANCE.measuringAudio){Toast.makeText(this,"Сначала завершите текущий сеанс CatchWave.",Toast.LENGTH_LONG).show();return;}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED&&!settings.prefs.getBoolean("notificationAsked",false)){
            settings.prefs.edit().putBoolean("notificationAsked",true).apply();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},24);return;
        }
        settings.prefs.edit().putBoolean("resume",true).apply();LanAudioService.maybeAutoStart(this);render();
    }
    private void stop(){settings.prefs.edit().putBoolean("resume",false).apply();stopService(new Intent(this,LanAudioService.class));}
    private void refresh(){if(state.started)startService(new Intent(this,LanAudioService.class).setAction(LanAudioService.REFRESH));render();}
    private boolean effectiveControl(){return LanConnection.effectiveControl(state.connected,settings.hostControl(),state.hostAllowed);}
    private void render(){
        if(isFinishing()||status==null)return;
        status.setText(state.status);detail.setText(state.detail);sensors.setText(state.sensorDetail);statistics.setText(state.statistics);action.setText(state.action);
        connect.setText(state.started?"Отключиться":state.error==LanConnection.Error.NONE?"Подключиться":"Повторить подключение");scan.setEnabled(!state.started);
        boolean effective=effectiveControl();control.setText(effective?"Выключить управление хостом":"Включить управление хостом");control.setEnabled(state.connected&&state.hostAllowed);
        String reason=!state.connected?"Нет свежего соединения":!state.hostAllowed?"Управление запрещено в текущем Host на ПК":settings.hostControl()?"Разрешение подтверждено на обеих сторонах":"Включите управление этой кнопкой";
        controlStatus.setText((effective?"Управление включено":"Управление выключено")+"\n"+reason+(!state.connected&&settings.hostControl()?". Сохранён запрос включения после доверенного подключения":""));
        advanced.setText(settings.paired()?settings.host()+":"+settings.port()+"\nSHA-256:\n"+fingerprint():"ПК ещё не сопряжён");
    }
    private String fingerprint(){try{StringBuilder b=new StringBuilder();for(byte v:settings.pin()){if(b.length()>0)b.append(':');b.append(String.format(java.util.Locale.ROOT,"%02x",v&255));}return b.toString();}catch(IllegalArgumentException bad){return "Нужно новое QR-сопряжение";}}
    @Override protected void onActivityResult(int code,int result,Intent data){super.onActivityResult(code,result,data);if(code==42){state.initialize(settings);render();}}
    @Override public void onRequestPermissionsResult(int code,String[] p,int[] r){super.onRequestPermissionsResult(code,p,r);if(code==24)begin();}
    @Override protected void onResume(){super.onResume();LanAudioService.uiVisible=true;state.initialize(settings);LanAudioService.maybeAutoStart(this);refresh();}
    @Override protected void onPause(){LanAudioService.uiVisible=MainActivity.visible;refresh();super.onPause();}
    @Override protected void onDestroy(){state.listeners.remove(listener);super.onDestroy();}
}
