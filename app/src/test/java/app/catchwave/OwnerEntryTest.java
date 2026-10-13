// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class OwnerEntryTest {
    @Test public void anotherAppCannotLaunchPrivilegedActivity()throws Exception {
        var app=RuntimeEnvironment.getApplication();var pm=app.getPackageManager();
        assertFalse(pm.getActivityInfo(new ComponentName(app,MainActivity.class),0).exported);
        assertFalse(pm.getActivityInfo(new ComponentName(app,LanAudioActivity.class),0).exported);
        assertFalse(pm.getServiceInfo(new ComponentName(app,LanAudioService.class),0).exported);
    }
    @Test public void exportedLauncherLifecycleDoesNotGrantPresenceOrResumeAudio(){
        MainActivity.visible=false;LanAudioService.uiVisible=false;LanState.INSTANCE.started=false;
        var settings=new LanSettings(RuntimeEnvironment.getApplication());settings.prefs.edit().putBoolean("resume",true).apply();
        try(var life=Robolectric.buildActivity(HomeActivity.class).setup()){
            assertFalse(MainActivity.visible);assertFalse(LanAudioService.uiVisible);assertFalse(LanState.INSTANCE.started);
            assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
        }finally{settings.prefs.edit().remove("resume").apply();}
    }
}
