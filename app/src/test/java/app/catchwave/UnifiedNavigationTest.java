// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

import android.app.AlertDialog;
import android.view.View;
import android.widget.Button;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import java.lang.reflect.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class UnifiedNavigationTest {
    private ActivityController<LanAudioActivity> life;

    @Before
    public void setup() {
        LanState.INSTANCE.started = false;
        LanState.INSTANCE.connected = false;
        SessionModel.INSTANCE.running = false;
        SessionModel.INSTANCE.measuringAudio = false;
        new LanSettings(RuntimeEnvironment.getApplication()).prefs.edit().clear().apply();
    }

    @After
    public void cleanup() {
        LanState.INSTANCE.started = false;
        LanState.INSTANCE.connected = false;
        if (life != null) life.destroy();
        new LanSettings(RuntimeEnvironment.getApplication()).prefs.edit().clear().apply();
    }

    private Button scanner() throws Exception {
        Field f = LanAudioActivity.class.getDeclaredField("scan");
        f.setAccessible(true);
        return (Button) f.get(life.get());
    }

    @Test
    public void scannerRemainsReachableDuringPlaybackAndCancelPreservesSession() throws Exception {
        life = Robolectric.buildActivity(LanAudioActivity.class).create();
        LanState.INSTANCE.started = true;
        LanState.INSTANCE.connected = true;
        LanState.INSTANCE.stage = LanConnection.Stage.STREAMING;
        Method render = LanAudioActivity.class.getDeclaredMethod("render");
        render.setAccessible(true);
        render.invoke(life.get());
        Button scan = scanner();
        assertEquals(View.VISIBLE, scan.getVisibility());
        assertTrue(scan.isEnabled());
        scan.performClick();
        assertTrue(LanState.INSTANCE.started);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        assertTrue(LanState.INSTANCE.started);
        assertNull(Shadows.shadowOf(life.get()).getNextStartedActivity());
    }

    @Test
    public void disconnectedScannerOpensCameraWithoutStartingAudio() throws Exception {
        life = Robolectric.buildActivity(LanAudioActivity.class).create();
        scanner().performClick();
        assertEquals(
                PairingScanActivity.class.getName(),
                Shadows.shadowOf(life.get())
                        .getNextStartedActivity()
                        .getComponent()
                        .getClassName());
        assertFalse(LanState.INSTANCE.started);
        assertFalse(SessionModel.INSTANCE.running);
    }

    @Test
    public void homeDoesNotStartRecognitionOrLanOnCreation() {
        try (ActivityController<HomeActivity> home =
                Robolectric.buildActivity(HomeActivity.class).create()) {
            assertFalse(LanState.INSTANCE.started);
            assertFalse(SessionModel.INSTANCE.running);
            assertNotNull(home.get().findViewById(android.R.id.content));
        }
    }
}
