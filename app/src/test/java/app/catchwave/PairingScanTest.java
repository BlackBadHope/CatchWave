package app.catchwave;

import android.Manifest;
import android.app.AlertDialog;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PairingScanTest {
    private LanSettings settings;

    @Before
    public void setup() {
        settings = new LanSettings(RuntimeEnvironment.getApplication());
        settings.prefs.edit().clear().commit();
        LanState.INSTANCE.started = false;
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .denyPermissions(Manifest.permission.CAMERA);
    }

    private void decoded(PairingScanActivity a) throws Exception {
        var m = PairingScanActivity.class.getDeclaredMethod("confirm", String.class);
        m.setAccessible(true);
        String zero = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        m.invoke(a, "CW1/192.0.2.1/49372/" + zero + "/" + zero);
    }

    @Test
    public void scanNeedsOwnerConfirmationAndDoesNotStartAudio() throws Exception {
        var life = Robolectric.buildActivity(PairingScanActivity.class).create();
        try {
            decoded(life.get());
            assertFalse(settings.paired());
            ShadowAlertDialog.getLatestAlertDialog()
                    .getButton(AlertDialog.BUTTON_POSITIVE)
                    .performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue(settings.paired());
            assertFalse(settings.resume());
            assertFalse(LanState.INSTANCE.started);
            assertFalse(settings.hostControl());
            assertFalse(new LanSettings(life.get()).hostControl());
        } finally {
            life.destroy();
        }
    }

    @Test
    public void explicitOffSurvivesNewScanAndSettingsInstance() throws Exception {
        settings.hostControl(false);
        var life = Robolectric.buildActivity(PairingScanActivity.class).create();
        try {
            decoded(life.get());
            ShadowAlertDialog.getLatestAlertDialog()
                    .getButton(AlertDialog.BUTTON_POSITIVE)
                    .performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue(settings.paired());
            assertFalse(new LanSettings(life.get()).hostControl());
        } finally {
            life.destroy();
        }
    }

    @Test
    public void cancellationAndPermissionDenialLeavePairingUntouched() throws Exception {
        var life = Robolectric.buildActivity(PairingScanActivity.class).create();
        try {
            decoded(life.get());
            ShadowAlertDialog.getLatestAlertDialog()
                    .getButton(AlertDialog.BUTTON_NEGATIVE)
                    .performClick();
            assertFalse(settings.paired());
        } finally {
            life.destroy();
        }
        var denied = Robolectric.buildActivity(PairingScanActivity.class).create();
        try {
            denied.get()
                    .onRequestPermissionsResult(
                            41, new String[] {Manifest.permission.CAMERA}, new int[] {-1});
            assertTrue(denied.get().isFinishing());
            assertFalse(settings.paired());
        } finally {
            denied.destroy();
        }
    }
}
