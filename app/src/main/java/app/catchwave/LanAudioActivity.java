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
    private LanAudioView ui;private android.media.AudioManager audio;private long lastVolumeRead=-10000;private boolean scanAfterStop;private String controlFingerprint=L.t("Unavailable");
    @Override public void onCreate(Bundle saved){L.init(this);
        super.onCreate(saved);DiagnosticStore.start(this);settings=new LanSettings(this);state.initialize(settings);
        audio=(android.media.AudioManager)getSystemService(AUDIO_SERVICE);ui=new LanAudioView(this);setContentView(ui);
        new Thread(()->{try{String value=new LanControlIdentity().fingerprint();runOnUiThread(()->{controlFingerprint=value;render();});}catch(java.security.GeneralSecurityException|java.io.IOException ignored){}},"CatchWave-key-fingerprint").start();
        status=ui.status;detail=ui.detail;advanced=ui.advanced;sensors=ui.sensors;controlStatus=ui.controlStatus;
        statistics=ui.statistics;action=ui.action;guide=ui.guide;modeStatus=ui.modeStatus;modeDetail=ui.modeDetail;
        connect=ui.connect;scan=ui.scan;control=ui.control;mode=ui.mode;
        mode.setOnClickListener(v->{if(state.started)startService(new Intent(this,LanAudioService.class).setAction(state.armed?LanAudioService.DEACTIVATE:LanAudioService.ACTIVATE));});
        ui.home.setOnClickListener(v->CatchWaveUi.home(this));
        ui.usb.setOnClickListener(v->startActivity(new Intent(this,UsbConnectActivity.class)));
        scan.setOnClickListener(v->{
            if(!state.started)startActivityForResult(new Intent(this,PairingScanActivity.class),42);
            else CatchWaveUi.dialog(this).setTitle(L.t("Connect another PC?"))
                .setMessage(L.t("Current audio will stop. Then the camera opens for a new QR code."))
                .setPositiveButton(L.t("Disconnect and scan"),(d,w)->{scanAfterStop=true;stop();render();})
                .setNegativeButton(L.t("Cancel"),null).show();
        });
        connect.setOnClickListener(v->{if(state.started)stop();else begin();});
        control.setOnClickListener(v->{
            if(settings.hostControl()){settings.hostControl(false);refresh();}
            else if(state.connected&&state.hostAllowed)CatchWaveUi.dialog(this).setTitle(L.t("Enable host control?"))
                .setMessage(L.t("Taps will control pause and track switching on your PC."))
                .setPositiveButton(L.t("Enable"),(d,w)->{settings.hostControl(true);refresh();}).setNegativeButton(L.t("Cancel"),null).show();
        });
        ui.gestures.setChecked(settings.gestures());ui.sound.setChecked(settings.activationSound());ui.off.setChecked(settings.screenOff());
        ui.gestures.setOnCheckedChangeListener((button,value)->{settings.prefs.edit().putBoolean("gestures",value).apply();refresh();});
        ui.sound.setOnCheckedChangeListener((button,value)->{settings.prefs.edit().putBoolean("activationSound",value).apply();refresh();});
        ui.off.setOnCheckedChangeListener((button,value)->{settings.prefs.edit().putBoolean("screenOff",value).apply();refresh();});
        ui.neutral.setOnClickListener(v->{if(state.started)startService(new Intent(this,LanAudioService.class).setAction(LanAudioService.CALIBRATE));});
        ui.journal.setOnClickListener(v->{TextView text=new TextView(this);text.setText(DiagnosticStore.LOG.text());text.setTextIsSelectable(true);ScrollView view=new ScrollView(this);view.addView(text);CatchWaveUi.dialog(this).setTitle(L.t("Last 256 events — no audio or secrets")).setView(view).setPositiveButton(L.t("Close"),null).show();});
        ui.export.setOnClickListener(v->startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain").putExtra(Intent.EXTRA_TITLE,"CatchWave-diagnostics.txt"),43));
        ui.forget.setOnClickListener(v->CatchWaveUi.dialog(this).setTitle(L.t("Forget pairing with the PC?")).setMessage(L.t("The connection will end. The next connection will need the QR from the current Host."))
                .setPositiveButton(L.t("Forget"),(d,w)->{stop();settings.forget();state.reset(settings);render();}).setNegativeButton(L.t("Cancel"),null).show());
        ui.back.setOnClickListener(v->finish());state.listeners.add(listener);render();
    }
    private void begin(){
        if(!settings.paired()){startActivityForResult(new Intent(this,PairingScanActivity.class),42);return;}
        if(SessionModel.INSTANCE.running||SessionModel.INSTANCE.measuringAudio){Toast.makeText(this,L.t("First end the current CatchWave session."),Toast.LENGTH_LONG).show();return;}
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
        status.setText(switch(state.stage){case STREAMING -> L.t("Connected");case SILENCE -> L.t("Silence from PC");case BUFFERING -> L.t("Waiting for audio");case PHONE_AUDIO -> L.t("Phone audio");case ERROR -> L.t("Connection error");case DISCONNECTED,SCANNED -> L.t("Not connected");default -> L.t("Connecting…");});
        status.setTextColor(state.connected?LanAudioView.ACCENT:state.error!=LanConnection.Error.NONE?0xfff4ae98:LanAudioView.MUTED);
        detail.setText((state.started?L.t("Connection: ")+state.transport+"\n":"")+state.detail);sensors.setText(state.sensorDetail);statistics.setText(state.statistics);action.setText(state.action);
        ui.usb.setText(state.started&&"USB".equals(state.transport)?L.t("Connected via USB"):L.t("Connect via USB"));
        connect.setText(state.started?L.t("Disconnect"):state.error==LanConnection.Error.NONE?L.t("Connect"):L.t("Retry connection"));
        scan.setEnabled(true);scan.setVisibility(android.view.View.VISIBLE);ui.connectionButtonStyle(state.started);
        boolean effective=effectiveControl();control.setText(settings.hostControl()?L.t("Revoke control permission"):L.t("Allow control"));control.setEnabled(settings.hostControl()||state.connected&&state.hostAllowed);
        boolean available=effective&&settings.gestures()&&state.listening;
        modeStatus.setText(!state.connected?state.error==LanConnection.Error.NONE?L.t("Connect a PC"):L.t("Connection lost"):!state.hostAllowed?L.t("Allow on the PC"):!settings.hostControl()?L.t("Permission needed"):!settings.gestures()?L.t("Gestures are off"):!state.listening?L.t("Preparing audio"):!state.armed?L.t("Mode is off"):state.controlReady?L.t("Gestures ready"):L.t("Setting neutral"));
        modeStatus.setTextColor(state.armed&&!state.controlReady?0xfff4c46e:LanAudioView.TEXT);
        modeDetail.setText(!state.connected?state.error==LanConnection.Error.NONE?L.t("Connect to CatchWave Host"):""+LanConnection.remedy(state.error):!state.hostAllowed?L.t("Enable control in CatchWave Host on the computer."):!settings.hostControl()?L.t("Allow control in the section below."):!settings.gestures()?L.t("Turn on the «Allow gestures» switch."):!state.activationReady?L.t("Waiting for fresh sensor readings."):!state.armed?L.t("Press the button below, then hold still for 1.5 s."):state.controlReady?L.t("Screen facing you · top edge up"):L.t("Mode on. Hold still for 1.5 seconds for the initial setup."));
        mode.setText(state.armed?L.t("Turn off control"):L.t("Activate control"));mode.setEnabled(state.armed||available&&state.activationReady);mode.setAlpha(mode.isEnabled()?1f:.42f);
        ui.tiltImage.setAlpha(available?1f:.4f);ui.showBlockedHelp(state.connected&&!effective);
        long now=SystemClock.elapsedRealtime();
        if(audio!=null&&now-lastVolumeRead>=1000){lastVolumeRead=now;int current=audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC),maximum=Math.max(1,audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC));
            ui.volume.setText(Math.round(current*100f/maximum)+"%"+(current==0?L.t(" · muted"):""));ui.volume.setTextColor(current==0?0xfff4c46e:LanAudioView.ACCENT);}
        String reason=!state.connected?L.t("No fresh connection"):!state.hostAllowed?L.t("Control is not allowed in the current Host on the PC"):settings.hostControl()?L.t("Permission confirmed on both sides"):L.t("Enable control with this button");
        controlStatus.setText(state.arming+"\n"+reason+"\n"+state.diagnostic);
        guide.setText(guideText());
        advanced.setText((settings.paired()?settings.host()+":"+settings.port()+L.t("\nPC certificate SHA-256:\n")+fingerprint():L.t("PC not paired yet"))+L.t("\nPhone control key SHA-256:\n")+controlFingerprint);
        if(scanAfterStop&&!state.started){scanAfterStop=false;startActivityForResult(new Intent(this,PairingScanActivity.class),42);}
    }
    private String guideText(){
        if(state.stage==LanConnection.Stage.PHONE_AUDIO)return L.t("The phone has priority. PC audio returns automatically when playback on the phone ends. «Disconnect» cancels the return.");
        if(state.stage==LanConnection.Stage.BUFFERING)return L.t("Waiting for fresh audio. Commands are paused; a short pause does not require reconnecting.");
        if(!state.connected)return L.t("Activation unavailable: connect to the Host first.");
        if(!state.hostAllowed)return L.t("Activation unavailable: press «Enable host control» in the Host on the PC and confirm the dialog.");
        if(!settings.hostControl())return L.t("Activation unavailable: allow control with the button on this phone.");
        if(!settings.gestures())return L.t("Activation unavailable: turn on «Allow gestures».");
        String current=state.armed?L.t("Control activated. To turn it off, press «Turn off control». There is no timer; phone position keeps the mode."):L.t("Mode is off. Press «Activate control». No table pose or turn is needed first. Two short vibrations confirm activation.");
        return current+L.t("\nAfter activation, hold the phone still for 1.5 s for the initial neutral. For tilts, hold it screen toward you, top edge up. Tilting right about 30° and holding raises the volume, left lowers it, in steps every 0.4 s. Returning to neutral stops the change. Picking up and rotating do not block taps. After a large change of pose, pause briefly: the tilt direction updates automatically. Lying flat face up or down, the volume does not change. A shake or 3 short finger taps on the body: play/pause; 4: next; 5: previous; 6: mute/restore the phone. Tap 0.2–0.5 s apart, then pause 0.7 s.\nPC audio continues regardless of gestures. Revoking permission, disconnecting or a sensor failure turns control off. The mode is experimental; motion recognition still needs on-device testing.");
    }
    private String fingerprint(){try{StringBuilder b=new StringBuilder();for(byte v:settings.pin()){if(b.length()>0)b.append(':');b.append(String.format(java.util.Locale.ROOT,"%02x",v&255));}return b.toString();}catch(IllegalArgumentException bad){return L.t("New QR pairing needed");}}
    @Override protected void onActivityResult(int code,int result,Intent data){super.onActivityResult(code,result,data);if(code==42){state.initialize(settings);if(result==RESULT_OK&&!state.started)begin();render();}if(code==43&&result==RESULT_OK&&data!=null&&data.getData()!=null){try(java.io.OutputStream out=getContentResolver().openOutputStream(data.getData())){if(out!=null)out.write(DiagnosticStore.LOG.text().getBytes(java.nio.charset.StandardCharsets.UTF_8));Toast.makeText(this,L.t("Diagnostics saved"),Toast.LENGTH_SHORT).show();}catch(java.io.IOException|SecurityException failure){Toast.makeText(this,L.t("Could not save diagnostics"),Toast.LENGTH_LONG).show();}}}
    @Override public void onRequestPermissionsResult(int code,String[] p,int[] r){super.onRequestPermissionsResult(code,p,r);if(code==24)begin();}
    @Override protected void onResume(){super.onResume();LanAudioService.uiVisible=true;state.initialize(settings);LanAudioService.maybeAutoStart(this);refresh();}
    @Override protected void onPause(){LanAudioService.uiVisible=MainActivity.visible;refresh();super.onPause();}
    @Override protected void onDestroy(){state.listeners.remove(listener);super.onDestroy();}
}
