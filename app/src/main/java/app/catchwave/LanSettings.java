// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.Base64;
final class LanSettings {
    final SharedPreferences prefs;
    final Context context;
    private PairingCode temporary;
    LanSettings(Context context){
        this.context=context.getApplicationContext();
        prefs=context.getSharedPreferences("lan-audio",Context.MODE_PRIVATE);
        // Owner requested a new OFF default; migrate code19's implicit checked trust once.
        if(!prefs.getBoolean("explicitControlUi21",false))prefs.edit().putBoolean("explicitControlUi21",true).putBoolean("hostControl",false).putBoolean("resume",false).putLong("controlEpoch",controlEpoch()+1).apply();
    }
    LanSettings(Context context,PairingCode temporary){this(context);this.temporary=temporary;}
    boolean paired(){return temporary!=null||prefs.getBoolean("paired",false);}
    boolean gestures(){return prefs.getBoolean("gestures",true);}
    boolean activationSound(){return prefs.getBoolean("activationSound",true);}
    boolean hostControl(){return prefs.getBoolean("hostControl",false);}
    long controlEpoch(){return prefs.getLong("controlEpoch",0);}
    void hostControl(boolean enabled){prefs.edit().putBoolean("explicitControlUi21",true).putBoolean("hostControl",enabled).putLong("controlEpoch",controlEpoch()+1).apply();}
    boolean screenOff(){return prefs.getBoolean("screenOff",false);}
    boolean resume(){return prefs.getBoolean("resume",true);}
    String host(){return temporary!=null?temporary.host:prefs.getString("host","");}
    int port(){return temporary!=null?temporary.port:prefs.getInt("port",49372);}
    byte[] pin(){return temporary!=null?temporary.pin.clone():Base64.getDecoder().decode(prefs.getString("pin",""));}
    byte[] token(){return temporary!=null?temporary.token.clone():Base64.getDecoder().decode(prefs.getString("token",""));}
    void pair(String code){pair(PairingCode.parse(code));}
    void rememberUsbRoute(PairingCode code){
        // Called only after this owner-started USB session has authenticated and begun output.
        // Keep resume/control decisions and the live UI; learning a route never activates gestures.
        prefs.edit().putString("host",code.host).putInt("port",code.port).putString("pin",Base64.getEncoder().encodeToString(code.pin))
            .putString("token",Base64.getEncoder().encodeToString(code.token)).putBoolean("paired",true).apply();
    }
    void pair(PairingCode code){
        pair(code,hostControl());
    }
    void pair(PairingCode code,boolean trustedControl){
        prefs.edit().putString("host",code.host).putInt("port",code.port).putString("pin",Base64.getEncoder().encodeToString(code.pin))
            .putString("token",Base64.getEncoder().encodeToString(code.token)).putBoolean("paired",true).putBoolean("resume",false)
            .putBoolean("explicitControlUi21",true).putBoolean("hostControl",trustedControl).putLong("controlEpoch",controlEpoch()+1).remove("lastFailure").remove("lastStage").apply();
        LanState.INSTANCE.reset(this);
    }
    void forget(){prefs.edit().remove("host").remove("pin").remove("token").putBoolean("paired",false).putBoolean("resume",false).apply();}
}
