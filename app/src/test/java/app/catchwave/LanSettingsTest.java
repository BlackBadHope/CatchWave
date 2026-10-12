package app.catchwave;

import android.content.Context;
import android.content.Intent;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class LanSettingsTest {
    private Context context;

    @Before
    public void setup() {
        context = RuntimeEnvironment.getApplication();
        new LanSettings(context).prefs.edit().clear().commit();
        LanState.INSTANCE.started = false;
    }

    @Test
    public void defaultOnAndExplicitOffPersist() {
        LanSettings s = new LanSettings(context);
        assertTrue(s.gestures());
        assertFalse(s.screenOff());
        assertFalse(s.paired());
        s.prefs.edit().putBoolean("gestures", false).commit();
        assertFalse(new LanSettings(context).gestures());
    }

    @Test
    public void invalidPairingDoesNotCommit() {
        LanSettings s = new LanSettings(context);
        assertThrows(IllegalArgumentException.class, () -> s.pair("CW1/192.168.1.2/49372/bad/bad"));
        assertFalse(s.paired());
    }

    @Test
    public void forgetClearsCredentialButKeepsExplicitOff() {
        LanSettings s = new LanSettings(context);
        String value =
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        s.pair("CW1/192.168.1.2/49372/" + value + "/" + value);
        assertTrue(s.paired());
        s.prefs.edit().putBoolean("gestures", false).commit();
        s.forget();
        assertFalse(s.paired());
        assertFalse(s.gestures());
        assertFalse(s.prefs.contains("token"));
        assertFalse(s.prefs.contains("pin"));
    }
}
