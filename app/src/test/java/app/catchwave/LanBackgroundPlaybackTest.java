package app.catchwave;

import android.os.*;
import android.net.wifi.WifiManager;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.time.Duration;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

/** The real service's health/lifecycle boundary, without network, audio or physical device changes. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=35) @LooperMode(LooperMode.Mode.PAUSED)
public class LanBackgroundPlaybackTest {
    private ServiceController<LanAudioService> life;private LanAudioService service;
    private LanSettings settings;private LanReceiver receiver;private LanFreshStream stream;private boolean ended;
    private static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void field(Object o,String name,Object v)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,v);}
    @Before public void setup()throws Exception{
        ShadowSystemClock.advanceBy(Duration.ofMillis(1000));
        life=Robolectric.buildService(LanAudioService.class).create();service=life.get();settings=new LanSettings(service);
        settings.prefs.edit().clear().putBoolean("explicitControlUi21",true).putBoolean("resume",true).putBoolean("gestures",false).putBoolean("screenOff",false).commit();
        receiver=new LanReceiver(settings,new LanReceiver.Listener(){public boolean ready(){return false;}public void connected(){}public void finished(){}public void ack(boolean a){}});
        byte[] body=new byte[24];ByteBuffer.wrap(body,16,8).putInt(48000).put((byte)2).put((byte)16).putShort((short)960);
        field(receiver,"format",new LanProtocol.Format(body));field(receiver,"latestSequence",0L);
        stream=(LanFreshStream)field(receiver,"stream");stream.received(SystemClock.elapsedRealtime());field(service,"receiver",receiver);
        LanState.INSTANCE.started=true;LanState.INSTANCE.connected=true;LanState.INSTANCE.error=LanConnection.Error.NONE;LanAudioService.uiVisible=false;
    }
    @After public void cleanup(){if(!ended)life.destroy();settings.prefs.edit().clear().commit();LanAudioService.uiVisible=false;}
    private void health()throws Exception{((Runnable)field(service,"health")).run();}
    @Test public void audioHoldsCpuWithBackgroundGesturesDisabled()throws Exception{
        health();PowerManager.WakeLock lock=(PowerManager.WakeLock)field(service,"lock");assertNotNull("Audio must own CPU independently of gesture preferences",lock);assertTrue(lock.isHeld());assertFalse(settings.screenOff());
    }
    @Test public void disablingBackgroundGesturesCannotReleaseActiveAudioCpu()throws Exception{
        settings.prefs.edit().putBoolean("screenOff",true).commit();health();PowerManager.WakeLock lock=(PowerManager.WakeLock)field(service,"lock");assertTrue(lock.isHeld());
        settings.prefs.edit().putBoolean("screenOff",false).commit();health();assertTrue("Gesture settings must not stop background audio power",lock.isHeld());
    }
    @Test public void shortFreshnessGapDoesNotTerminateAudioOrClearResume()throws Exception{
        health();ShadowSystemClock.advanceBy(Duration.ofMillis(800));health();assertFalse(receiver.fresh());
        assertFalse("Short stalls need recovery, not a new owner connection",Shadows.shadowOf(service).isStoppedBySelf());assertTrue(settings.resume());assertEquals(LanConnection.Error.NONE,LanState.INSTANCE.error);
    }
    @Test public void audioCpuDoesNotDependOnFiveSecondMainThreadRenewal()throws Exception{
        health();PowerManager.WakeLock lock=(PowerManager.WakeLock)field(service,"lock");
        for(int i=0;i<300;i++){ShadowSystemClock.advanceBy(Duration.ofMillis(20));stream.received(SystemClock.elapsedRealtime());}
        assertTrue("Continuous audio must stay awake while the UI callback is delayed",lock.isHeld());assertTrue(receiver.fresh());
    }
    @Test public void streamOwnsWifiAndOwnerStopReleasesBothLeases()throws Exception{
        health();PowerManager.WakeLock lock=(PowerManager.WakeLock)field(service,"lock");WifiManager.WifiLock wifi=(WifiManager.WifiLock)field(service,"wifiLock");
        assertTrue(lock.isHeld());assertNotNull(wifi);assertTrue(wifi.isHeld());
        service.onStartCommand(new android.content.Intent().setAction(LanAudioService.STOP),0,2);assertFalse(settings.resume());
        life.destroy();ended=true;assertFalse(lock.isHeld());assertFalse(wifi.isHeld());assertFalse(receiver.playbackAlive());
    }
    @Test public void unrecoveredLongGapStopsServiceAndTeardownReleasesPower()throws Exception{
        health();PowerManager.WakeLock lock=(PowerManager.WakeLock)field(service,"lock");WifiManager.WifiLock wifi=(WifiManager.WifiLock)field(service,"wifiLock");
        ShadowSystemClock.advanceBy(Duration.ofMillis(5000));health();assertTrue(Shadows.shadowOf(service).isStoppedBySelf());assertFalse(settings.resume());assertEquals(LanConnection.Error.AUDIO_STALLED,LanState.INSTANCE.error);
        life.destroy();ended=true;assertFalse(lock.isHeld());assertFalse(wifi.isHeld());
    }
}
