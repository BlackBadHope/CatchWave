// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.*;
import android.view.View;

/** One entry point for CatchWave's two listening paths. Opening a path never starts recording. */
public final class HomeActivity extends Activity {
    @Override public void onCreate(Bundle saved){L.init(this);
        super.onCreate(saved);LinearLayout body=CatchWaveUi.column(this);CatchWaveUi.page(this,body);CatchWaveUi.header(this,body,null);
        CatchWaveUi.add(body,CatchWaveUi.text(this,L.t("Music nearby"),32,CatchWaveUi.TEXT,true),28);
        CatchWaveUi.add(body,CatchWaveUi.text(this,L.t("From your computer or around you"),15,CatchWaveUi.MUTED,false),8);
        CatchWaveUi.divider(body,24);
        CatchWaveUi.add(body,CatchWaveUi.text(this,L.t("PC audio"),24,CatchWaveUi.TEXT,true),24);
        CatchWaveUi.add(body,CatchWaveUi.text(this,L.t("Listen to your computer over Wi-Fi. Control volume and tracks from your phone."),14,CatchWaveUi.MUTED,false),8);
        ImageView image=new ImageView(this);image.setImageResource(R.drawable.lan_tilt_guide);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(-1,CatchWaveUi.dp(this,138));ip.topMargin=CatchWaveUi.dp(this,12);body.addView(image,ip);
        Button pc=CatchWaveUi.button(this,L.t("Open PC audio"),true);pc.setOnClickListener(v->startActivity(new Intent(this,LanAudioActivity.class)));CatchWaveUi.add(body,pc,8);
        CatchWaveUi.divider(body,24);
        CatchWaveUi.add(body,CatchWaveUi.text(this,L.t("Music around you"),24,CatchWaveUi.TEXT,true),24);
        CatchWaveUi.add(body,CatchWaveUi.text(this,L.t("Recognize a song and continue from the same moment in YouTube Music."),14,CatchWaveUi.MUTED,false),8);
        Button recognize=CatchWaveUi.button(this,L.t("Open recognition"),false);recognize.setOnClickListener(v->startActivity(new Intent(this,MainActivity.class)));CatchWaveUi.add(body,recognize,16);
        CatchWaveUi.divider(body,24);
        Button settings=CatchWaveUi.button(this,L.t("Settings and help"),false);settings.setOnClickListener(v->startActivity(new Intent(this,MainActivity.class).putExtra("openSettings",true)));CatchWaveUi.add(body,settings,16);
        // Shown in both languages so either reader can find it. English is the default.
        Button language=CatchWaveUi.button(this,L.russian()?"Язык: русский · English":"Language: English · Русский",false);
        language.setOnClickListener(v->{L.set(this,!L.russian());recreate();});CatchWaveUi.add(body,language,8);
        CatchWaveUi.add(body,CatchWaveUi.text(this,AppIdentity.label(),11,CatchWaveUi.MUTED,false),20);
    }
    // Exported launcher visibility is not a fresh owner action. Private screens are entered by buttons.
}
