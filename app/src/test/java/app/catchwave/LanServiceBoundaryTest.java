package app.catchwave;

import android.app.Service;
import android.content.Intent;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ServiceController;
import java.lang.reflect.Field;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LanServiceBoundaryTest {
    private ServiceController<LanAudioService> life;
    private LanAudioService service;
    private LanSettings settings;

    @Before
    public void setup() {
        SessionModel.INSTANCE.running = false;
        SessionModel.INSTANCE.measuringAudio = false;
        LanState.INSTANCE.started = false;
        LanState.INSTANCE.connected = false;
        life = Robolectric.buildService(LanAudioService.class).create();
        service = life.get();
        settings = new LanSettings(service);
        settings.prefs.edit().clear().putBoolean("paired", true).putBoolean("resume", true).apply();
    }

    @After
    public void cleanup() {
        life.destroy();
        settings.prefs.edit().clear().apply();
        SessionModel.INSTANCE.running = false;
        SessionModel.INSTANCE.measuringAudio = false;
    }

    private void blocked() throws Exception {
        assertEquals(
                Service.START_NOT_STICKY,
                service.onStartCommand(new Intent().setAction(LanAudioService.START), 0, 1));
        assertFalse(LanState.INSTANCE.started);
        for (String name : new String[] {"receiver", "focus"}) {
            Field f = LanAudioService.class.getDeclaredField(name);
            f.setAccessible(true);
            assertNull(f.get(service));
        }
    }

    @Test
    public void queuedStartIsCancelledWhenResumeWasDisabled() throws Exception {
        settings.prefs.edit().putBoolean("resume", false).apply();
        blocked();
    }

    @Test
    public void staleRefreshAfterDisconnectCannotKeepServiceStarted() {
        settings.prefs.edit().putBoolean("resume", false).apply();
        assertEquals(
                Service.START_NOT_STICKY,
                service.onStartCommand(new Intent().setAction(LanAudioService.REFRESH), 0, 1));
        assertTrue(Shadows.shadowOf(service).isStoppedBySelf());
    }

    @Test
    public void syncSessionBlocksLanBeforeNetworkAndAudioFocus() throws Exception {
        SessionModel.INSTANCE.running = true;
        blocked();
    }

    @Test
    public void audioMeasurementBlocksLanBeforeNetworkAndAudioFocus() throws Exception {
        SessionModel.INSTANCE.measuringAudio = true;
        blocked();
    }

    @Test
    public void syncStartCancelsPendingLanEvenWhenSyncCannotAcquirePermissions() throws Exception {
        ServiceController<SyncService> sync = Robolectric.buildService(SyncService.class).create();
        try {
            sync.get().onStartCommand(new Intent().setAction(SyncService.START), 0, 1);
            assertFalse(settings.resume());
            blocked();
        } finally {
            sync.destroy();
        }
    }

    @Test
    public void microphoneOpenRejectsRevokedPermissionBeforeCreatingAudioRecord() throws Exception {
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .denyPermissions(android.Manifest.permission.RECORD_AUDIO);
        ServiceController<SyncService> sync = Robolectric.buildService(SyncService.class).create();
        try {
            java.lang.reflect.Method open =
                    SyncService.class.getDeclaredMethod("openMicrophone", int.class);
            open.setAccessible(true);
            try {
                open.invoke(sync.get(), 64000);
                fail("Revoked microphone permission must be rejected");
            } catch (java.lang.reflect.InvocationTargetException failure) {
                assertTrue(failure.getCause() instanceof SecurityException);
            }
        } finally {
            sync.destroy();
        }
    }
}
