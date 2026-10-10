package app.catchwave;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Looper;
import android.widget.*;
import java.io.*;
import java.net.*;
import javax.net.ssl.*;
import java.security.cert.*;
import java.lang.reflect.Field;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class LanConnectionTest {
    private LanSettings settings;private final LanState state=LanState.INSTANCE;
    @Before public void setup(){settings=new LanSettings(RuntimeEnvironment.getApplication());settings.prefs.edit().clear().commit();state.started=false;state.reset(settings);SessionModel.INSTANCE.running=false;SessionModel.INSTANCE.measuringAudio=false;}
    @After public void cleanup(){state.started=false;state.reset(settings);settings.prefs.edit().clear().commit();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private void pair(){String zero="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";settings.pair("CW1/192.0.2.1/49372/"+zero+"/"+zero);}
    private Object field(Activity a,String name)throws Exception{Field f=a.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(a);}
    @Test public void startupDelayDiscardsBacklogThenLiveStallGuardStillWorks(){
        LanTimeline timeline=new LanTimeline(0,1000);
        // Focus+output consumed 800ms while Host kept sending 20ms frames.
        int skipped=0;long stamp=0;while(!timeline.startupFresh(stamp,1800)){stamp+=20;skipped++;}
        assertEquals(35,skipped);assertEquals(700,stamp);timeline.arm(stamp,1800);
        assertFalse(timeline.stalled(stamp+20,1820));assertTrue(timeline.stalled(stamp+40,2400));
        assertTrue((1800-1000)-(20-0)>500); // code19's original live check rejects this healthy startup.
    }
    @Test public void firstTerminalErrorSurvivesCleanupAndInitialization(){
        pair();state.started=true;state.transition(LanConnection.Stage.OUTPUT);state.fail(settings,LanConnection.Error.PCM_OUTPUT,LanConnection.Stage.OUTPUT);
        state.stopped(settings);state.fail(settings,LanConnection.Error.HOST_CLOSED,LanConnection.Stage.STREAMING);state.initialize(settings);
        assertEquals(LanConnection.Error.PCM_OUTPUT,state.error);assertEquals(LanConnection.Stage.ERROR,state.stage);assertFalse(settings.resume());assertFalse(state.connected);assertFalse(state.hostAllowed);
        assertEquals("PCM_OUTPUT",settings.prefs.getString("lastFailure",""));assertTrue(state.detail.contains("OUTPUT"));
    }
    @Test public void stopFailureAndResetCannotKeepStaleCountdownOrOutputLabel(){state.arming="Управление: 10 с";state.diagnostic="AudioTrack принимает звук";state.stopped(settings);assertEquals("Выключено",state.arming);assertEquals("Вывод остановлен",state.diagnostic);state.fail(settings,LanConnection.Error.AUDIO_FOCUS_LOST,LanConnection.Stage.STREAMING);assertTrue(state.diagnostic.contains("AUDIO_FOCUS_LOST"));state.reset(settings);assertEquals("Выключено",state.arming);assertEquals("Вывод не запущен",state.diagnostic);}
    @Test public void diagnosticClassifiesFailuresWithoutCopyingPayload(){
        String privatePayload="private endpoint or secret";
        assertEquals(LanConnection.Error.CONNECT_REFUSED,LanFailure.classify(new ConnectException(privatePayload),LanConnection.Stage.TCP));
        assertEquals(LanConnection.Error.CONNECT_TIMEOUT,LanFailure.classify(new SocketTimeoutException(privatePayload),LanConnection.Stage.TCP));
        assertEquals(LanConnection.Error.PIN_MISMATCH,LanFailure.classify(new SSLHandshakeException(privatePayload){{initCause(new LanFailure.PinMismatch());}},LanConnection.Stage.TLS));
        assertEquals(LanConnection.Error.CERT_TIME,LanFailure.classify(new CertificateExpiredException(privatePayload),LanConnection.Stage.TLS));
        state.fail(settings,LanConnection.Error.CONNECT_REFUSED,LanConnection.Stage.TCP);assertFalse(state.detail.contains(privatePayload));
    }
    @Test public void scannedAndDisconnectedNeverShowEffectiveControl()throws Exception{
        pair();settings.hostControl(true);state.initialize(settings);
        var life=Robolectric.buildActivity(LanAudioActivity.class).create();try{
            Activity a=life.get();assertEquals(LanConnection.Stage.SCANNED,state.stage);
            assertTrue(((Button)field(a,"control")).isEnabled());assertEquals("Выключить управление",((Button)field(a,"control")).getText().toString());
            assertTrue(((TextView)field(a,"controlStatus")).getText().toString().startsWith("Выключено"));
        }finally{life.destroy();}
    }
    @Test public void livePolicyAndExplicitConfirmationDriveStableActionButton()throws Exception{
        pair();state.connected=true;state.hostAllowed=true;state.started=true;
        var life=Robolectric.buildActivity(LanAudioActivity.class).create();try{
            Activity a=life.get();Button b=(Button)field(a,"control");assertTrue(b.isEnabled());assertFalse(settings.hostControl());
            b.performClick();assertFalse(settings.hostControl());ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(settings.hostControl());assertEquals("Выключить управление",b.getText().toString());
            b.performClick();assertFalse(settings.hostControl());assertEquals("Разрешить управление",b.getText().toString());
            state.hostAllowed=false;state.changed();Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse(b.isEnabled());assertTrue(((TextView)field(a,"controlStatus")).getText().toString().contains("запрещено"));
        }finally{life.destroy();}
    }
    @Test public void implicitCode19OnMigratesOffOnceAndExplicitChoiceSurvives(){
        settings.prefs.edit().remove("explicitControlUi21").putBoolean("hostControl",true).putBoolean("resume",true).commit();LanSettings migrated=new LanSettings(RuntimeEnvironment.getApplication());assertFalse(migrated.hostControl());assertFalse(migrated.resume());
        migrated.hostControl(true);assertTrue(new LanSettings(RuntimeEnvironment.getApplication()).hostControl());
    }
}
