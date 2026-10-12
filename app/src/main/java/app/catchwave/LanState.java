// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.concurrent.CopyOnWriteArrayList;

final class LanState {
    static final LanState INSTANCE=new LanState();
    volatile boolean started,connected,listening,hostAllowed,armed;
    volatile boolean controlReady,activationReady;
    volatile String controlReadiness="OFF";
    volatile LanConnection.Stage stage=LanConnection.Stage.DISCONNECTED,lastStage=LanConnection.Stage.DISCONNECTED;
    volatile LanConnection.Error error=LanConnection.Error.NONE;
    volatile String status=LanConnection.title(stage),detail="Сканируйте QR текущего CatchWave Host.",sensorDetail="Датчики выключены",action="",statistics="Поток не запущен";
    volatile int sensorCode;
    volatile String arming="Выключено",diagnostic="Вывод не запущен";
    final CopyOnWriteArrayList<Runnable> listeners=new CopyOnWriteArrayList<>();
    private final Handler main=new Handler(Looper.getMainLooper());
    void initialize(LanSettings settings) {
        if(started||error!=LanConnection.Error.NONE)return;
        try { error=LanConnection.Error.valueOf(settings.prefs.getString("lastFailure","NONE"));
            lastStage=LanConnection.Stage.valueOf(settings.prefs.getString("lastStage","DISCONNECTED"));
        }catch(IllegalArgumentException invalid){error=LanConnection.Error.NONE;}
        if(error!=LanConnection.Error.NONE){stage=LanConnection.Stage.ERROR;status=LanConnection.title(stage);detail=error+" · этап "+lastStage+"\n"+LanConnection.remedy(error);}
        else transition(settings.paired()?LanConnection.Stage.SCANNED:LanConnection.Stage.DISCONNECTED);
    }
    void transition(LanConnection.Stage value) {
        stage=value;status=LanConnection.title(value);
        detail=switch(value){
            case DISCONNECTED -> "Сканируйте QR текущего CatchWave Host.";
            case SCANNED -> "Сопряжение сохранено. Нажмите «Подключиться». Звук ещё не запущен.";
            case SILENCE -> "PCM получен и принят AudioTrack. Кадры содержат нулевые отсчёты.";
            case STREAMING -> "PCM получен и принят AudioTrack. Слышимость зависит от громкости и аудиовыхода телефона.";
            case BUFFERING -> "Краткая пауза в потоке. Ожидание до 5 с; устаревший звук отбрасывается, команды приостановлены.";
            default -> "Этап: "+value;
        };
        Log.i("CatchWaveLAN","stage="+value.name());changed();
    }
    void reset(LanSettings settings) {
        armed=controlReady=activationReady=false;controlReadiness="OFF";arming="Выключено";diagnostic="Вывод не запущен";
        error=LanConnection.Error.NONE;action="";settings.prefs.edit().remove("lastFailure").remove("lastStage").apply();
        connected=false;listening=false;hostAllowed=false;sensorCode=0;sensorDetail="Датчики выключены";statistics="Поток не запущен";transition(settings.paired()?LanConnection.Stage.SCANNED:LanConnection.Stage.DISCONNECTED);
    }
    void fail(LanSettings settings,LanConnection.Error code,LanConnection.Stage at) {
        if(error!=LanConnection.Error.NONE)return;
        error=code;lastStage=at;stage=LanConnection.Stage.ERROR;connected=false;listening=false;hostAllowed=false;armed=controlReady=activationReady=false;controlReadiness="OFF";arming="Выключено";diagnostic="Последняя причина: "+code;
        settings.prefs.edit().putBoolean("resume",false).putString("lastFailure",code.name()).putString("lastStage",at.name()).apply();
        status=LanConnection.title(stage);detail=code+" · этап "+at+"\n"+LanConnection.remedy(code);
        Log.w("CatchWaveLAN","error="+code.name()+" stage="+at.name());changed();
    }
    void stopped(LanSettings settings) {
        armed=controlReady=activationReady=false;controlReadiness="OFF";arming="Выключено";diagnostic=error==LanConnection.Error.NONE?"Вывод остановлен":("Последняя причина: "+error);
        started=false;connected=false;listening=false;hostAllowed=false;sensorCode=0;sensorDetail="Датчики выключены";
        if(error==LanConnection.Error.NONE){transition(settings.paired()?LanConnection.Stage.SCANNED:LanConnection.Stage.DISCONNECTED);detail="Отключено. Для подключения нажмите кнопку.";}changed();
    }
    void update(String status,String detail){action=status+". "+detail;changed();}
    void changed(){main.post(()->{for(Runnable listener:listeners)listener.run();});}
}
