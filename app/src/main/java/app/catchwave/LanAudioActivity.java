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
    private LanSettings settings;private TextView status,detail,advanced,sensors,controlStatus,statistics,action,guide,modeStatus,modeDetail;private Button connect,scan,control,mode;
    private LanAudioView ui;private android.media.AudioManager audio;private long lastVolumeRead=-10000;private boolean scanAfterStop;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);DiagnosticStore.start(this);settings=new LanSettings(this);state.initialize(settings);
        audio=(android.media.AudioManager)getSystemService(AUDIO_SERVICE);ui=new LanAudioView(this);setContentView(ui);
        status=ui.status;detail=ui.detail;advanced=ui.advanced;sensors=ui.sensors;controlStatus=ui.controlStatus;
        statistics=ui.statistics;action=ui.action;guide=ui.guide;modeStatus=ui.modeStatus;modeDetail=ui.modeDetail;
        connect=ui.connect;scan=ui.scan;control=ui.control;mode=ui.mode;
        mode.setOnClickListener(v->{if(state.started)startService(new Intent(this,LanAudioService.class).setAction(state.armed?LanAudioService.DEACTIVATE:LanAudioService.ACTIVATE));});
        ui.home.setOnClickListener(v->CatchWaveUi.home(this));
        scan.setOnClickListener(v->{
            if(!state.started)startActivityForResult(new Intent(this,PairingScanActivity.class),42);
            else CatchWaveUi.dialog(this).setTitle("Подключить другой ПК?")
                .setMessage("Текущий звук остановится. Затем откроется камера для нового QR-кода.")
                .setPositiveButton("Отключить и сканировать",(d,w)->{scanAfterStop=true;stop();render();})
                .setNegativeButton("Отмена",null).show();
        });
        connect.setOnClickListener(v->{if(state.started)stop();else begin();});
        control.setOnClickListener(v->{
            if(settings.hostControl()){settings.hostControl(false);refresh();}
            else if(state.connected&&state.hostAllowed)CatchWaveUi.dialog(this).setTitle("Включить управление хостом?")
                .setMessage("Постукивания будут управлять паузой и переключением треков на вашем ПК.")
                .setPositiveButton("Включить",(d,w)->{settings.hostControl(true);refresh();}).setNegativeButton("Отмена",null).show();
        });
        ui.gestures.setChecked(settings.gestures());ui.sound.setChecked(settings.activationSound());ui.off.setChecked(settings.screenOff());
        ui.gestures.setOnCheckedChangeListener((button,value)->{settings.prefs.edit().putBoolean("gestures",value).apply();refresh();});
        ui.sound.setOnCheckedChangeListener((button,value)->{settings.prefs.edit().putBoolean("activationSound",value).apply();refresh();});
        ui.off.setOnCheckedChangeListener((button,value)->{settings.prefs.edit().putBoolean("screenOff",value).apply();refresh();});
        ui.neutral.setOnClickListener(v->{if(state.started)startService(new Intent(this,LanAudioService.class).setAction(LanAudioService.CALIBRATE));});
        ui.journal.setOnClickListener(v->{TextView text=new TextView(this);text.setText(DiagnosticStore.LOG.text());text.setTextIsSelectable(true);ScrollView view=new ScrollView(this);view.addView(text);CatchWaveUi.dialog(this).setTitle("Последние 256 событий — без аудио и секретов").setView(view).setPositiveButton("Закрыть",null).show();});
        ui.export.setOnClickListener(v->startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain").putExtra(Intent.EXTRA_TITLE,"CatchWave-diagnostics.txt"),43));
        ui.forget.setOnClickListener(v->CatchWaveUi.dialog(this).setTitle("Забыть сопряжение с ПК?").setMessage("Соединение завершится. Для следующего подключения понадобится QR текущего Host.")
                .setPositiveButton("Забыть",(d,w)->{stop();settings.forget();state.reset(settings);render();}).setNegativeButton("Отмена",null).show());
        ui.back.setOnClickListener(v->finish());state.listeners.add(listener);render();
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
        status.setText(switch(state.stage){case STREAMING -> "Подключено";case SILENCE -> "Тишина с ПК";case BUFFERING -> "Ждём звук";case ERROR -> "Ошибка связи";case DISCONNECTED,SCANNED -> "Не подключено";default -> "Подключение…";});
        status.setTextColor(state.connected?LanAudioView.ACCENT:state.error!=LanConnection.Error.NONE?0xfff4ae98:LanAudioView.MUTED);
        detail.setText(state.detail);sensors.setText(state.sensorDetail);statistics.setText(state.statistics);action.setText(state.action);
        connect.setText(state.started?"Отключиться":state.error==LanConnection.Error.NONE?"Подключиться":"Повторить подключение");
        scan.setEnabled(true);scan.setVisibility(android.view.View.VISIBLE);ui.connectionButtonStyle(state.started);
        boolean effective=effectiveControl();control.setText(settings.hostControl()?"Отозвать разрешение управления":"Разрешить управление");control.setEnabled(settings.hostControl()||state.connected&&state.hostAllowed);
        boolean available=effective&&settings.gestures()&&state.listening;
        modeStatus.setText(!state.connected?state.error==LanConnection.Error.NONE?"Подключите ПК":"Связь прервалась":!state.hostAllowed?"Разрешите на ПК":!settings.hostControl()?"Нужно разрешение":!settings.gestures()?"Жесты выключены":!state.listening?"Готовим звук":!state.armed?"Режим выключен":state.controlReady?"Жесты готовы":"Настраиваем нейтраль");
        modeStatus.setTextColor(state.armed&&!state.controlReady?0xfff4c46e:LanAudioView.TEXT);
        modeDetail.setText(!state.connected?state.error==LanConnection.Error.NONE?"Подключитесь к CatchWave Host":""+LanConnection.remedy(state.error):!state.hostAllowed?"Включите управление в CatchWave Host на компьютере.":!settings.hostControl()?"Разрешите управление в разделе ниже.":!settings.gestures()?"Включите переключатель «Разрешить жесты».":!state.activationReady?"Ожидаем свежие показания датчиков.":!state.armed?"Нажмите кнопку ниже, затем держите спокойно 1,5 с.":state.controlReady?"Экран к себе · верхним краем вверх":"Режим включён. Держите спокойно 1,5 секунды для начальной настройки.");
        mode.setText(state.armed?"Выключить управление":"Активировать управление");mode.setEnabled(state.armed||available&&state.activationReady);mode.setAlpha(mode.isEnabled()?1f:.42f);
        ui.tiltImage.setAlpha(available?1f:.4f);ui.showBlockedHelp(state.connected&&!effective);
        long now=SystemClock.elapsedRealtime();
        if(audio!=null&&now-lastVolumeRead>=1000){lastVolumeRead=now;int current=audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC),maximum=Math.max(1,audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC));
            ui.volume.setText(Math.round(current*100f/maximum)+"%"+(current==0?" · без звука":""));ui.volume.setTextColor(current==0?0xfff4c46e:LanAudioView.ACCENT);}
        String reason=!state.connected?"Нет свежего соединения":!state.hostAllowed?"Управление запрещено в текущем Host на ПК":settings.hostControl()?"Разрешение подтверждено на обеих сторонах":"Включите управление этой кнопкой";
        controlStatus.setText(state.arming+"\n"+reason+"\n"+state.diagnostic);
        guide.setText(guideText());
        advanced.setText(settings.paired()?settings.host()+":"+settings.port()+"\nSHA-256:\n"+fingerprint():"ПК ещё не сопряжён");
        if(scanAfterStop&&!state.started){scanAfterStop=false;startActivityForResult(new Intent(this,PairingScanActivity.class),42);}
    }
    private String guideText(){
        if(state.stage==LanConnection.Stage.BUFFERING)return "Ожидается свежий звук. Команды приостановлены; короткая пауза не требует повторного подключения.";
        if(!state.connected)return "Активация недоступна: сначала подключитесь к Host.";
        if(!state.hostAllowed)return "Активация недоступна: нажмите «Включить управление хостом» в Host на ПК и подтвердите диалог.";
        if(!settings.hostControl())return "Активация недоступна: разрешите управление кнопкой на этом телефоне.";
        if(!settings.gestures())return "Активация недоступна: включите «Разрешить жесты».";
        String current=state.armed?"Управление активировано. Для выключения нажмите «Выключить управление». Таймера нет; положение телефона сохраняет режим.":"Режим выключен. Нажмите «Активировать управление». Предварительное положение на столе и поворот не нужны. Две короткие вибрации подтверждают включение.";
        return current+"\nПосле включения держите телефон спокойно 1,5 с для начальной нейтрали. Для наклонов — экраном к себе, верхним краем вверх. Наклон вправо примерно на 30° с удержанием прибавляет громкость, влево — убавляет, шагами каждые 0,4 с. Возврат в нейтраль останавливает изменение. Поднятие и поворот не блокируют постукивания. При большой смене позы ненадолго остановите движение: направление наклонов обновится автоматически. На столе экраном вверх или вниз громкость не меняется. Встряхивание или 3 коротких постукивания пальцем по корпусу: play/pause; 4: next; 5: previous; 6: mute/restore телефона. Интервал постукиваний 0,2–0,5 с, затем пауза 0,7 с.\nЗвук с ПК продолжается независимо от жестов. Отзыв разрешения, отключение соединения или сбой датчиков выключают управление. Режим экспериментален; распознавание движений ещё требует проверки на устройстве.";
    }
    private String fingerprint(){try{StringBuilder b=new StringBuilder();for(byte v:settings.pin()){if(b.length()>0)b.append(':');b.append(String.format(java.util.Locale.ROOT,"%02x",v&255));}return b.toString();}catch(IllegalArgumentException bad){return "Нужно новое QR-сопряжение";}}
    @Override protected void onActivityResult(int code,int result,Intent data){super.onActivityResult(code,result,data);if(code==42){state.initialize(settings);render();}if(code==43&&result==RESULT_OK&&data!=null&&data.getData()!=null){try(java.io.OutputStream out=getContentResolver().openOutputStream(data.getData())){if(out!=null)out.write(DiagnosticStore.LOG.text().getBytes(java.nio.charset.StandardCharsets.UTF_8));Toast.makeText(this,"Диагностика сохранена",Toast.LENGTH_SHORT).show();}catch(java.io.IOException|SecurityException failure){Toast.makeText(this,"Не удалось сохранить диагностику",Toast.LENGTH_LONG).show();}}}
    @Override public void onRequestPermissionsResult(int code,String[] p,int[] r){super.onRequestPermissionsResult(code,p,r);if(code==24)begin();}
    @Override protected void onResume(){super.onResume();LanAudioService.uiVisible=true;state.initialize(settings);LanAudioService.maybeAutoStart(this);refresh();}
    @Override protected void onPause(){LanAudioService.uiVisible=MainActivity.visible;refresh();super.onPause();}
    @Override protected void onDestroy(){state.listeners.remove(listener);super.onDestroy();}
}
