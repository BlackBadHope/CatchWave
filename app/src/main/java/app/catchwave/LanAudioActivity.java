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
        super.onCreate(saved);DiagnosticStore.start(this);settings=new LanSettings(this);state.initialize(settings);
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
            if(settings.hostControl()){settings.hostControl(false);refresh();}
            else if(state.connected&&state.hostAllowed)new AlertDialog.Builder(this).setTitle("Включить управление хостом?")
                .setMessage("Жесты play/pause, next и previous будут управлять проигрывателем на вашем ПК.")
                .setPositiveButton("Включить",(d,w)->{settings.hostControl(true);refresh();}).setNegativeButton("Отмена",null).show();
        });body.addView(control);
        Switch gestures=new Switch(this);gestures.setText("Разрешить жесты");gestures.setChecked(settings.gestures());body.addView(gestures);
        gestures.setOnCheckedChangeListener((button,value)->{settings.prefs.edit().putBoolean("gestures",value).apply();refresh();});
        Switch sound=new Switch(this);sound.setText("Звук открытия и закрытия жестового окна");sound.setChecked(settings.activationSound());body.addView(sound);
        sound.setOnCheckedChangeListener((button,value)->{settings.prefs.edit().putBoolean("activationSound",value).apply();refresh();});
        TextView guide=new TextView(this);guide.setText("Все команды заблокированы до активации. Разрешите управление на обеих сторонах, положите телефон экраном вниз почти горизонтально на 0,85 с, затем поверните на столе примерно на 90° в любую сторону и остановитесь на 0,25 с. Две короткие вибрации: окно управления 10 с. Обычное движение его не продлевает. Возврат экраном вниз к исходному углу или устойчивый переворот экраном вверх блокирует команды.\nПосле активации и после поднятия держите спокойно 1,5 с для свежей нейтрали. Медленно наклоните вправо/влево примерно на 30° и удерживайте: громкость шагами каждые 0,4 с. Возврат в нейтраль останавливает наклон. Встряхивание или 3 постукивания: play/pause; 4: next; 5: previous; 6: mute/restore телефона. Постукивайте с промежутками 0,2–0,5 с, затем пауза 0,7 с.\nЗвук не зависит от блокировки жестов. Выключайте разрешение управления вне намеренного использования. Режим экспериментален; случайные жесты ещё требуют проверки на устройстве.");body.addView(guide);
        TextView soundGuide=new TextView(this);soundGuide.setText("Короткий тон вверх: открыто окно 10 с; вниз: закрыто. Разрешение управления само по себе не звучит. Громкость сигнала зависит от текущей media volume; после открытия всё ещё нужна спокойная нейтраль. Устойчивое почти горизонтальное положение экраном вверх закрывает окно даже в руке.");body.addView(soundGuide);
        Button neutral=new Button(this);neutral.setText("Сбросить активацию и нейтраль");neutral.setOnClickListener(v->{if(state.started)startService(new Intent(this,LanAudioService.class).setAction(LanAudioService.CALIBRATE));});body.addView(neutral);
        LinearLayout extras=new LinearLayout(this);extras.setOrientation(LinearLayout.VERTICAL);extras.setVisibility(android.view.View.GONE);
        Button more=new Button(this);more.setText("Дополнительно");more.setOnClickListener(v->extras.setVisibility(extras.getVisibility()==android.view.View.GONE?android.view.View.VISIBLE:android.view.View.GONE));body.addView(more);body.addView(extras);
        advanced=new TextView(this);extras.addView(advanced);
        Button journal=new Button(this);journal.setText("Показать журнал диагностики");journal.setOnClickListener(v->{TextView text=new TextView(this);text.setText(DiagnosticStore.LOG.text());text.setTextIsSelectable(true);ScrollView view=new ScrollView(this);view.addView(text);new AlertDialog.Builder(this).setTitle("Последние 256 событий — без аудио и секретов").setView(view).setPositiveButton("Закрыть",null).show();});extras.addView(journal);
        Button export=new Button(this);export.setText("Экспорт диагностики");export.setOnClickListener(v->startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain").putExtra(Intent.EXTRA_TITLE,"CatchWave-diagnostics.txt"),43));extras.addView(export);
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
        boolean effective=effectiveControl();control.setText(settings.hostControl()?"Отозвать разрешение управления":"Разрешить управление");control.setEnabled(settings.hostControl()||state.connected&&state.hostAllowed);
        String reason=!state.connected?"Нет свежего соединения":!state.hostAllowed?"Управление запрещено в текущем Host на ПК":settings.hostControl()?"Разрешение подтверждено на обеих сторонах":"Включите управление этой кнопкой";
        controlStatus.setText(state.arming+"\n"+reason+"\n"+state.diagnostic);
        advanced.setText(settings.paired()?settings.host()+":"+settings.port()+"\nSHA-256:\n"+fingerprint():"ПК ещё не сопряжён");
    }
    private String fingerprint(){try{StringBuilder b=new StringBuilder();for(byte v:settings.pin()){if(b.length()>0)b.append(':');b.append(String.format(java.util.Locale.ROOT,"%02x",v&255));}return b.toString();}catch(IllegalArgumentException bad){return "Нужно новое QR-сопряжение";}}
    @Override protected void onActivityResult(int code,int result,Intent data){super.onActivityResult(code,result,data);if(code==42){state.initialize(settings);render();}if(code==43&&result==RESULT_OK&&data!=null&&data.getData()!=null){try(java.io.OutputStream out=getContentResolver().openOutputStream(data.getData())){if(out!=null)out.write(DiagnosticStore.LOG.text().getBytes(java.nio.charset.StandardCharsets.UTF_8));Toast.makeText(this,"Диагностика сохранена",Toast.LENGTH_SHORT).show();}catch(java.io.IOException|SecurityException failure){Toast.makeText(this,"Не удалось сохранить диагностику",Toast.LENGTH_LONG).show();}}}
    @Override public void onRequestPermissionsResult(int code,String[] p,int[] r){super.onRequestPermissionsResult(code,p,r);if(code==24)begin();}
    @Override protected void onResume(){super.onResume();LanAudioService.uiVisible=true;state.initialize(settings);LanAudioService.maybeAutoStart(this);refresh();}
    @Override protected void onPause(){LanAudioService.uiVisible=MainActivity.visible;refresh();super.onPause();}
    @Override protected void onDestroy(){state.listeners.remove(listener);super.onDestroy();}
}
