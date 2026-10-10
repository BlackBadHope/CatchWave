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
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(24,24,24,24);
        TextView title=new TextView(this);title.setText("Сканируйте QR в локальном окне CatchWave Host на своём ПК.");body.addView(title);
        scanner=new DecoratedBarcodeView(this);scanner.getBarcodeView().setDecoderFactory(new DefaultDecoderFactory(Collections.singletonList(BarcodeFormat.QR_CODE)));
        scanner.setStatusText("QR остаётся только на ваших устройствах");body.addView(scanner,new LinearLayout.LayoutParams(-1,0,1));
        Button cancel=new Button(this);cancel.setText("Отмена");cancel.setOnClickListener(v->finish());body.addView(cancel);setContentView(body);
        scanner.decodeSingle(result->confirm(result.getText()));
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.CAMERA},41);
    }
    private void confirm(String value){
        if(confirming||isFinishing())return;scanner.pause();
        final PairingCode candidate;
        try{candidate=PairingCode.parse(value);}catch(IllegalArgumentException bad){
            confirming=true;new AlertDialog.Builder(this).setTitle("Это не QR CatchWave Host").setMessage("Наведите камеру на QR в окне своего Host.")
                .setPositiveButton("Повторить",(d,w)->retry()).setNegativeButton("Отмена",(d,w)->finish()).setOnCancelListener(d->finish()).show();return;
        }
        confirming=true;
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(32,16,32,16);
        TextView message=new TextView(this);message.setText("Подключить этот ПК? Подтверждайте только QR, который вы открыли на своём компьютере.");panel.addView(message);
        LanSettings current=new LanSettings(this);
        CheckBox trust=new CheckBox(this);trust.setText("Разрешить жестами управление плеером этого ПК");trust.setChecked(current.hostControl());panel.addView(trust);
        TextView advanced=new TextView(this);advanced.setText(candidate.host+":"+candidate.port+"\nSHA-256:\n"+candidate.fingerprint());advanced.setVisibility(android.view.View.GONE);
        Button more=new Button(this);more.setText("Дополнительно");more.setOnClickListener(v->advanced.setVisibility(advanced.getVisibility()==android.view.View.GONE?android.view.View.VISIBLE:android.view.View.GONE));panel.addView(more);panel.addView(advanced);
        new AlertDialog.Builder(this).setTitle("CatchWave Host").setView(panel)
            .setPositiveButton("Подтвердить",(d,w)->{if(!LanState.INSTANCE.started){new LanSettings(this).pair(candidate,trust.isChecked());setResult(RESULT_OK);}finish();})
            .setNegativeButton("Отмена",(d,w)->finish()).setOnCancelListener(d->finish()).show();
    }
    private void retry(){confirming=false;scanner.decodeSingle(result->confirm(result.getText()));if(hasCamera())scanner.resume();}
    private boolean hasCamera(){return checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED;}
    @Override protected void onResume(){super.onResume();if(hasCamera()&&!confirming)scanner.resume();}
    @Override protected void onPause(){scanner.pause();super.onPause();}
    @Override public void onRequestPermissionsResult(int code,String[] p,int[] r){super.onRequestPermissionsResult(code,p,r);if(code==41){if(hasCamera())scanner.resume();else{Toast.makeText(this,"Без разрешения камеры QR нельзя отсканировать.",Toast.LENGTH_LONG).show();finish();}}}
}
