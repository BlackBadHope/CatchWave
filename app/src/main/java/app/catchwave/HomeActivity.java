// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.*;
import android.view.View;

/** One entry point for CatchWave's two listening paths. Opening a path never starts recording. */
public final class HomeActivity extends Activity {
    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout body = CatchWaveUi.column(this);
        CatchWaveUi.page(this, body);
        CatchWaveUi.header(this, body, null);
        CatchWaveUi.add(
                body, CatchWaveUi.text(this, "Музыка рядом", 32, CatchWaveUi.TEXT, true), 28);
        CatchWaveUi.add(
                body,
                CatchWaveUi.text(this, "С компьютера или вокруг вас", 15, CatchWaveUi.MUTED, false),
                8);
        CatchWaveUi.divider(body, 24);
        CatchWaveUi.add(body, CatchWaveUi.text(this, "Звук с ПК", 24, CatchWaveUi.TEXT, true), 24);
        CatchWaveUi.add(
                body,
                CatchWaveUi.text(
                        this,
                        "Слушайте компьютер по Wi-Fi. Управляйте громкостью и треками с телефона.",
                        14,
                        CatchWaveUi.MUTED,
                        false),
                8);
        ImageView image = new ImageView(this);
        image.setImageResource(R.drawable.lan_tilt_guide);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, CatchWaveUi.dp(this, 138));
        ip.topMargin = CatchWaveUi.dp(this, 12);
        body.addView(image, ip);
        Button pc = CatchWaveUi.button(this, "Открыть звук с ПК", true);
        pc.setOnClickListener(v -> startActivity(new Intent(this, LanAudioActivity.class)));
        CatchWaveUi.add(body, pc, 8);
        CatchWaveUi.divider(body, 24);
        CatchWaveUi.add(
                body, CatchWaveUi.text(this, "Музыка вокруг", 24, CatchWaveUi.TEXT, true), 24);
        CatchWaveUi.add(
                body,
                CatchWaveUi.text(
                        this,
                        "Распознайте песню и продолжите с того же момента в YouTube Music.",
                        14,
                        CatchWaveUi.MUTED,
                        false),
                8);
        Button recognize = CatchWaveUi.button(this, "Открыть распознавание", false);
        recognize.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        CatchWaveUi.add(body, recognize, 16);
        CatchWaveUi.divider(body, 24);
        Button settings = CatchWaveUi.button(this, "Настройки и помощь", false);
        settings.setOnClickListener(
                v ->
                        startActivity(
                                new Intent(this, MainActivity.class)
                                        .putExtra("openSettings", true)));
        CatchWaveUi.add(body, settings, 16);
        CatchWaveUi.add(
                body,
                CatchWaveUi.text(this, AppIdentity.label(), 11, CatchWaveUi.MUTED, false),
                20);
    }

    @Override
    protected void onResume() {
        super.onResume();
        MainActivity.visible = true;
        LanAudioService.uiVisible = true;
        LanAudioService.maybeAutoStart(this);
        if (LanState.INSTANCE.started)
            startService(
                    new Intent(this, LanAudioService.class).setAction(LanAudioService.REFRESH));
    }

    @Override
    protected void onPause() {
        MainActivity.visible = false;
        LanAudioService.uiVisible = false;
        if (LanState.INSTANCE.started)
            startService(
                    new Intent(this, LanAudioService.class).setAction(LanAudioService.REFRESH));
        super.onPause();
    }
}
