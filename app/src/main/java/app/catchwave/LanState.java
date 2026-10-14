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
    volatile String transport="Wi-Fi";
    volatile LanConnection.Stage stage=LanConnection.Stage.DISCONNECTED,lastStage=LanConnection.Stage.DISCONNECTED;
    volatile LanConnection.Error error=LanConnection.Error.NONE;
    volatile String status=LanConnection.title(stage),detail=L.t("Scan the QR of the current CatchWave Host."),sensorDetail=L.t("Sensors off"),action="",statistics=L.t("Stream not started");
    volatile int sensorCode;
    volatile String arming=L.t("Off"),diagnostic=L.t("Output not started");
    final CopyOnWriteArrayList<Runnable> listeners=new CopyOnWriteArrayList<>();
    private final Handler main=new Handler(Looper.getMainLooper());
    void initialize(LanSettings settings) {
        if(started||error!=LanConnection.Error.NONE)return;
        try { error=LanConnection.Error.valueOf(settings.prefs.getString("lastFailure","NONE"));
            lastStage=LanConnection.Stage.valueOf(settings.prefs.getString("lastStage","DISCONNECTED"));
        }catch(IllegalArgumentException invalid){error=LanConnection.Error.NONE;}
        if(error!=LanConnection.Error.NONE){stage=LanConnection.Stage.ERROR;status=LanConnection.title(stage);detail=error+L.t(" · stage ")+lastStage+"\n"+LanConnection.remedy(error);}
        else transition(settings.paired()?LanConnection.Stage.SCANNED:LanConnection.Stage.DISCONNECTED);
    }
    void transition(LanConnection.Stage value) {
        stage=value;status=LanConnection.title(value);
        detail=switch(value){
            case DISCONNECTED -> L.t("Scan the QR of the current CatchWave Host.");
            case SCANNED -> L.t("Pairing saved. Press «Connect». Audio has not started yet.");
            case SILENCE -> L.t("PCM received and accepted by AudioTrack. Frames contain zero samples.");
            case STREAMING -> L.t("PCM received and accepted by AudioTrack. Audibility depends on the phone's volume and audio output.");
            case BUFFERING -> L.t("Brief pause in the stream. Waiting up to 5 s; stale audio is dropped and commands are paused.");
            case PHONE_AUDIO -> L.t("PC audio is paused for another app. When the phone audio ends, CatchWave continues automatically.");
            default -> L.t("Stage: ")+value;
        };
        Log.i("CatchWaveLAN","stage="+value.name());changed();
    }
    void reset(LanSettings settings) {
        armed=controlReady=activationReady=false;controlReadiness="OFF";arming=L.t("Off");diagnostic=L.t("Output not started");
        error=LanConnection.Error.NONE;action="";settings.prefs.edit().remove("lastFailure").remove("lastStage").apply();
        connected=false;listening=false;hostAllowed=false;sensorCode=0;sensorDetail=L.t("Sensors off");statistics=L.t("Stream not started");transition(settings.paired()?LanConnection.Stage.SCANNED:LanConnection.Stage.DISCONNECTED);
    }
    void fail(LanSettings settings,LanConnection.Error code,LanConnection.Stage at) {
        if(error!=LanConnection.Error.NONE)return;
        error=code;lastStage=at;stage=LanConnection.Stage.ERROR;connected=false;listening=false;hostAllowed=false;armed=controlReady=activationReady=false;controlReadiness="OFF";arming=L.t("Off");diagnostic=L.t("Last reason: ")+code;
        settings.prefs.edit().putBoolean("resume",false).putString("lastFailure",code.name()).putString("lastStage",at.name()).apply();
        status=LanConnection.title(stage);detail=code+L.t(" · stage ")+at+"\n"+LanConnection.remedy(code);
        Log.w("CatchWaveLAN","error="+code.name()+" stage="+at.name());changed();
    }
    void stopped(LanSettings settings) {
        armed=controlReady=activationReady=false;controlReadiness="OFF";arming=L.t("Off");diagnostic=error==LanConnection.Error.NONE?L.t("Output stopped"):(L.t("Last reason: ")+error);
        started=false;connected=false;listening=false;hostAllowed=false;sensorCode=0;sensorDetail=L.t("Sensors off");
        if(error==LanConnection.Error.NONE){transition(settings.paired()?LanConnection.Stage.SCANNED:LanConnection.Stage.DISCONNECTED);detail=L.t("Disconnected. Press the button to connect.");}changed();
    }
    void update(String status,String detail){action=status+". "+detail;changed();}
    void changed(){main.post(()->{for(Runnable listener:listeners)listener.run();});}
}
