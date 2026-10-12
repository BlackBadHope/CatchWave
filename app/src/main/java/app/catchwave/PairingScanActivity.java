// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;
import android.Manifest;
import android.app.*;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.*;
import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.*;
import java.util.Collections;

/** Local camera only: frames, decoded QR and credential are never logged or uploaded. */
public final class PairingScanActivity extends Activity {
    private DecoratedBarcodeView scanner;private boolean confirming;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout body=CatchWaveUi.column(this);body.setBackgroundColor(CatchWaveUi.BG);body.setPadding(CatchWaveUi.dp(this,22),CatchWaveUi.dp(this,14),CatchWaveUi.dp(this,22),CatchWaveUi.dp(this,24));
        body.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets safe=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());v.setPadding(CatchWaveUi.dp(this,22)+safe.left,CatchWaveUi.dp(this,14)+safe.top,CatchWaveUi.dp(this,22)+safe.right,CatchWaveUi.dp(this,24)+safe.bottom);return insets;});
        CatchWaveUi.header(this,body,null);
        CatchWaveUi.add(body,CatchWaveUi.text(this,"Подключить компьютер",26,CatchWaveUi.TEXT,true),20);
        CatchWaveUi.add(body,CatchWaveUi.text(this,"Наведите камеру на QR в окне CatchWave Host на вашем ПК.",14,CatchWaveUi.MUTED,false),10);
        scanner=new DecoratedBarcodeView(this);scanner.getBarcodeView().setDecoderFactory(new DefaultDecoderFactory(Collections.singletonList(BarcodeFormat.QR_CODE)));
        scanner.setStatusText("QR остаётся только на ваших устройствах");body.addView(scanner,new LinearLayout.LayoutParams(-1,0,1));
        Button cancel=CatchWaveUi.button(this,"Отмена",false);cancel.setOnClickListener(v->finish());CatchWaveUi.add(body,cancel,14);setContentView(body);body.requestApplyInsets();
        scanner.decodeSingle(result->confirm(result.getText()));
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.CAMERA},41);
    }
    private void confirm(String value){
        if(confirming||isFinishing())return;scanner.pause();
        final PairingCode candidate;
        try{candidate=PairingCode.parse(value);}catch(IllegalArgumentException bad){
            confirming=true;CatchWaveUi.dialog(this).setTitle("Это не QR CatchWave Host").setMessage("Наведите камеру на QR в окне своего Host.")
                .setPositiveButton("Повторить",(d,w)->retry()).setNegativeButton("Отмена",(d,w)->finish()).setOnCancelListener(d->finish()).show();return;
        }
        confirming=true;
        LinearLayout panel=CatchWaveUi.column(this);panel.setPadding(CatchWaveUi.dp(this,22),CatchWaveUi.dp(this,12),CatchWaveUi.dp(this,22),CatchWaveUi.dp(this,16));
        TextView message=CatchWaveUi.text(this,"Подтверждайте только QR, который вы открыли на своём компьютере.",14,CatchWaveUi.TEXT,false);panel.addView(message);
        LanSettings current=new LanSettings(this);
        CheckBox trust=new CheckBox(this);trust.setText("Разрешить жестами управление плеером этого ПК");trust.setChecked(current.hostControl());panel.addView(trust);
        TextView advanced=CatchWaveUi.text(this,candidate.host+":"+candidate.port+"\nSHA-256:\n"+candidate.fingerprint(),12,CatchWaveUi.MUTED,false);advanced.setVisibility(android.view.View.GONE);
        Button more=CatchWaveUi.button(this,"Параметры подключения",false);more.setOnClickListener(v->advanced.setVisibility(advanced.getVisibility()==android.view.View.GONE?android.view.View.VISIBLE:android.view.View.GONE));CatchWaveUi.add(panel,more,12);CatchWaveUi.add(panel,advanced,8);
        CatchWaveUi.dialog(this).setTitle("CatchWave Host").setView(panel)
            .setPositiveButton("Подтвердить",(d,w)->{if(!LanState.INSTANCE.started){new LanSettings(this).pair(candidate,trust.isChecked());setResult(RESULT_OK);}finish();})
            .setNegativeButton("Отмена",(d,w)->finish()).setOnCancelListener(d->finish()).show();
    }
    private void retry(){confirming=false;scanner.decodeSingle(result->confirm(result.getText()));if(hasCamera())scanner.resume();}
    private boolean hasCamera(){return checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED;}
    @Override protected void onResume(){super.onResume();if(hasCamera()&&!confirming)scanner.resume();}
    @Override protected void onPause(){scanner.pause();super.onPause();}
    @Override public void onRequestPermissionsResult(int code,String[] p,int[] r){super.onRequestPermissionsResult(code,p,r);if(code==41){if(hasCamera())scanner.resume();else{Toast.makeText(this,"Без разрешения камеры QR нельзя отсканировать.",Toast.LENGTH_LONG).show();finish();}}}
}
