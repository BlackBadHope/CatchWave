// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

import android.app.*;
import android.content.*;
import android.hardware.usb.*;
import android.os.Bundle;
import android.widget.*;

/** Private owner-button entry; exported attach entry may only continue an already active session. */
public class UsbConnectActivity extends Activity {
    private static final String PERMISSION="app.catchwave.USB_PERMISSION";
    private UsbManager manager;private UsbAccessory selected;private boolean waiting;
    private final BroadcastReceiver permission=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){
        if(!PERMISSION.equals(intent.getAction())||!waiting)return;waiting=false;
        if(current()&&manager.hasPermission(selected))connect();else Toast.makeText(UsbConnectActivity.this,L.t("USB access not allowed"),Toast.LENGTH_LONG).show();
    }};
    @Override public void onCreate(Bundle saved){L.init(this);super.onCreate(saved);manager=getSystemService(UsbManager.class);
        // Ignore incoming parcelables from other applications. Select only the current OS accessory list.
        UsbAccessory[] list=manager.getAccessoryList();if(list!=null)for(UsbAccessory a:list)if(UsbAudioProxy.matches(a)){selected=a;break;}
        registerReceiver(permission,new IntentFilter(PERMISSION),Context.RECEIVER_NOT_EXPORTED);
        LinearLayout body=CatchWaveUi.column(this);CatchWaveUi.page(this,body);CatchWaveUi.add(body,CatchWaveUi.text(this,L.t("USB connection"),25,CatchWaveUi.TEXT,true),24);
        CatchWaveUi.add(body,CatchWaveUi.text(this,current()?L.t("CatchWave Host found over the cable. The connection needs neither debugging nor USB tethering."):L.t("Connect the cable and press «Connect via USB» in CatchWave Host on the PC. After the system prompt, choose CatchWave."),15,CatchWaveUi.MUTED,false),16);
        Button go=CatchWaveUi.button(this,L.t("Connect audio via USB"),true);go.setEnabled(current());go.setOnClickListener(v->request());CatchWaveUi.add(body,go,24);
        Button cancel=CatchWaveUi.button(this,L.t("Back"),false);cancel.setOnClickListener(v->finish());CatchWaveUi.add(body,cancel,12);
        if(autoAllowed()&&current())request();
    }
    protected boolean autoAllowed(){return true;}
    private void request(){
            if(waiting||isFinishing())return;
            if(!current()){Toast.makeText(this,L.t("Cable disconnected"),Toast.LENGTH_LONG).show();return;}
            if(manager.hasPermission(selected))connect();else {
                waiting=true;Intent request=new Intent(PERMISSION).setPackage(getPackageName());
                PendingIntent reply=PendingIntent.getBroadcast(this,37,request,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
                manager.requestPermission(selected,reply);
            }
    }
    private boolean current(){
        // equals() may read the protected serial before the first USB permission is granted.
        // Android exposes one actual accessory; refresh it from UsbManager, never from an intent.
        UsbAccessory[] list=manager.getAccessoryList();if(list==null)return false;
        for(UsbAccessory accessory:list)if(UsbAudioProxy.matches(accessory)){selected=accessory;return true;}
        return false;
    }
    private void connect(){
        if(!current()||!manager.hasPermission(selected))return;
        if(SessionModel.INSTANCE.running||SessionModel.INSTANCE.measuringAudio){Toast.makeText(this,L.t("First stop recognition or audio measurement"),Toast.LENGTH_LONG).show();return;}
        LanSettings settings=new LanSettings(this);settings.prefs.edit().putBoolean("resume",true).apply();
        startForegroundService(new Intent(this,LanAudioService.class).setAction(LanAudioService.USB).putExtra(UsbManager.EXTRA_ACCESSORY,selected));
        startActivity(new Intent(this,LanAudioActivity.class));finish();
    }
    @Override protected void onDestroy(){waiting=false;unregisterReceiver(permission);super.onDestroy();}
}
