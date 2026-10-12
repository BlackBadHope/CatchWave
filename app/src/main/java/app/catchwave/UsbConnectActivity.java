// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

import android.app.*;
import android.content.*;
import android.hardware.usb.*;
import android.os.Bundle;
import android.widget.*;

/** A narrow exported USB entry. A real attached accessory and an owner button are both required. */
public final class UsbConnectActivity extends Activity {
    private static final String PERMISSION="app.catchwave.USB_PERMISSION";
    private UsbManager manager;private UsbAccessory selected;private boolean waiting;
    private final BroadcastReceiver permission=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){
        if(!PERMISSION.equals(intent.getAction())||!waiting)return;waiting=false;
        if(current()&&manager.hasPermission(selected))connect();else Toast.makeText(UsbConnectActivity.this,"USB-доступ не разрешён",Toast.LENGTH_LONG).show();
    }};
    @Override public void onCreate(Bundle saved){super.onCreate(saved);manager=getSystemService(UsbManager.class);
        // Ignore incoming parcelables from other applications. Select only the current OS accessory list.
        UsbAccessory[] list=manager.getAccessoryList();if(list!=null)for(UsbAccessory a:list)if(UsbAudioProxy.matches(a)){selected=a;break;}
        registerReceiver(permission,new IntentFilter(PERMISSION),Context.RECEIVER_NOT_EXPORTED);
        LinearLayout body=CatchWaveUi.column(this);CatchWaveUi.page(this,body);CatchWaveUi.add(body,CatchWaveUi.text(this,"Подключение по USB",25,CatchWaveUi.TEXT,true),24);
        CatchWaveUi.add(body,CatchWaveUi.text(this,current()?"CatchWave Host найден по кабелю. Подключение не требует отладки или USB-модема.":"Подключите кабель и нажмите «Подключить по USB» в CatchWave Host на ПК. После системного запроса выберите CatchWave.",15,CatchWaveUi.MUTED,false),16);
        Button go=CatchWaveUi.button(this,"Подключить звук по USB",true);go.setEnabled(current());go.setOnClickListener(v->{
            if(!current()){Toast.makeText(this,"Кабель отключён",Toast.LENGTH_LONG).show();return;}
            if(manager.hasPermission(selected))connect();else {
                waiting=true;Intent request=new Intent(PERMISSION).setPackage(getPackageName());
                PendingIntent reply=PendingIntent.getBroadcast(this,37,request,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
                manager.requestPermission(selected,reply);
            }
        });CatchWaveUi.add(body,go,24);
        Button cancel=CatchWaveUi.button(this,"Вернуться",false);cancel.setOnClickListener(v->finish());CatchWaveUi.add(body,cancel,12);
    }
    private boolean current(){if(selected==null)return false;UsbAccessory[] list=manager.getAccessoryList();if(list==null)return false;for(UsbAccessory a:list)if(a.equals(selected))return true;return false;}
    private void connect(){
        if(!current()||!manager.hasPermission(selected))return;
        if(SessionModel.INSTANCE.running||SessionModel.INSTANCE.measuringAudio){Toast.makeText(this,"Сначала остановите распознавание или измерение звука",Toast.LENGTH_LONG).show();return;}
        LanSettings settings=new LanSettings(this);settings.prefs.edit().putBoolean("resume",true).apply();
        startForegroundService(new Intent(this,LanAudioService.class).setAction(LanAudioService.USB).putExtra(UsbManager.EXTRA_ACCESSORY,selected));
        startActivity(new Intent(this,LanAudioActivity.class));finish();
    }
    @Override protected void onDestroy(){waiting=false;unregisterReceiver(permission);super.onDestroy();}
}
