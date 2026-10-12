package app.catchwave;

import android.media.AudioManager;
import android.os.*;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

/** Robolectric volume is fake: these tests never contact a real Android device. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=35) @LooperMode(LooperMode.Mode.PAUSED)
public class HeldServiceTest {
    ServiceController<LanAudioService> life;LanAudioService service;LanSettings settings;LanReceiver receiver;AudioManager manager;
    static void field(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    static byte[] policy(boolean allowed,long generation){return ByteBuffer.allocate(9).put((byte)(allowed?1:0)).putLong(generation).array();}
    @Before public void setup()throws Exception{
        life=Robolectric.buildService(LanAudioService.class).create();service=life.get();settings=new LanSettings(service);
        settings.prefs.edit().clear().putBoolean("explicitControlUi21",true).putBoolean("gestures",true).putBoolean("screenOff",true).putBoolean("resume",true).commit();settings.hostControl(true);
        receiver=new LanReceiver(settings,new LanReceiver.Listener(){public boolean ready(){return false;}public void connected(){}public void finished(){}public void ack(boolean a){}
            public void policy(boolean allowed){new Handler(Looper.getMainLooper()).post(()->LanState.INSTANCE.hostAllowed=allowed);}});
        byte[] format=new byte[24];ByteBuffer b=ByteBuffer.wrap(format);b.position(16);b.putInt(48000).put((byte)2).put((byte)16).putShort((short)960);
        field(receiver,"format",new LanProtocol.Format(format));field(receiver,"latestSequence",0L);
        ((LanFreshStream)field(receiver,"stream")).received(SystemClock.elapsedRealtime());receiver.policy(policy(true,0));
        field(service,"receiver",receiver);field(service,"registered",true);LanState.INSTANCE.connected=true;LanState.INSTANCE.hostAllowed=true;LanAudioService.uiVisible=true;
        field(service,"orientationRegistered",true);field(service,"seenEpoch",settings.controlEpoch());field(service,"seenPolicy",receiver.policyGeneration());arm();
        ((LanFreshStream)field(receiver,"stream")).received(SystemClock.elapsedRealtime());
        manager=service.getSystemService(AudioManager.class);manager.setStreamVolume(AudioManager.STREAM_MUSIC,10,0);assertTrue(receiver.fresh());assertTrue(receiver.localControlAllowed());
    }
    @After public void cleanup(){life.destroy();settings.prefs.edit().clear().commit();LanAudioService.uiVisible=false;}
    void arm()throws Exception{
        GestureArming gate=(GestureArming)field(service,"arming");long start=SystemClock.elapsedRealtime();gate.permit(true,start);
        double c=Math.sqrt(.5);gate.sample(start+1250,0,c,-c,0,0,9.81);assertTrue(gate.activate(start+1250));
        GestureEngine e=(GestureEngine)field(service,"engine");e.setEnabled(true,start+1250);
        for(long n=1300;n<=2900;n+=50){gate.sample(start+n,0,c,-c,0,0,9.81);e.gyro(0,0,0);e.orientation(start+n,0,c,-c,0);e.acceleration(start+n,0,0,-9.81);}
        field(service,"armRevision",gate.revision());ShadowSystemClock.advanceBy(Duration.ofMillis(2900));assertTrue(e.neutralReady(SystemClock.elapsedRealtime()));assertTrue(gate.commands(SystemClock.elapsedRealtime()));
    }
    @Test public void unarmedSessionRejectsAllCommandsIncludingMute()throws Exception{((GestureArming)field(service,"arming")).reset("TEST_LOCK");for(GestureEngine.Action a:GestureEngine.Action.values())gesture(a);assertEquals(10,manager.getStreamVolume(AudioManager.STREAM_MUSIC));assertEquals(0L,field(service,"gestureActions"));}
    @Test public void fixedDeadlineBlocksWithoutAHealthCallback()throws Exception{ShadowSystemClock.advanceBy(Duration.ofMillis(11000));((LanFreshStream)field(receiver,"stream")).received(SystemClock.elapsedRealtime());gesture(GestureEngine.Action.MUTE);assertEquals(10,manager.getStreamVolume(AudioManager.STREAM_MUSIC));}
    @Test public void manualNeutralInvalidatesDequeuedCommandRevision()throws Exception{
        GestureArming gate=(GestureArming)field(service,"arming");LanReceiver gated=new LanReceiver(settings,new LanReceiver.Listener(){public boolean ready(){return false;}public void connected(){}public void finished(){}public void ack(boolean a){}},()->gate.commands(SystemClock.elapsedRealtime()),gate::revision);
        long old=gate.revision();assertTrue(gated.commandArmed(old));service.onStartCommand(new android.content.Intent().setAction(LanAudioService.CALIBRATE),0,1);assertFalse(gated.commandArmed(old));assertFalse(gate.active(SystemClock.elapsedRealtime()));
    }
    void gesture(GestureEngine.Action action)throws Exception{Method m=LanAudioService.class.getDeclaredMethod("gesture",GestureEngine.Action.class);m.setAccessible(true);m.invoke(service,action);}
    @Test public void receiverOffBlocksLocalStepBeforeQueuedUiFlagChanges()throws Exception{
        gesture(GestureEngine.Action.VOLUME_DOWN);assertTrue(manager.getStreamVolume(AudioManager.STREAM_MUSIC)<10);int current=manager.getStreamVolume(AudioManager.STREAM_MUSIC);
        receiver.policy(policy(false,1));assertTrue(LanState.INSTANCE.hostAllowed);assertFalse(receiver.localControlAllowed());
        gesture(GestureEngine.Action.VOLUME_UP);assertEquals(current,manager.getStreamVolume(AudioManager.STREAM_MUSIC));assertEquals(1L,field(service,"volumeAdjustments"));
    }
    @Test public void oldGenerationCannotReviveAfterOffOnAndPhoneOffStopsImmediately()throws Exception{
        long generation=receiver.policyGeneration(),epoch=settings.controlEpoch();receiver.policy(policy(false,1));receiver.policy(policy(true,2));AtomicInteger calls=new AtomicInteger();
        assertFalse(receiver.executeLocalControl(epoch,generation,calls::incrementAndGet));assertEquals(0,calls.get());
        settings.hostControl(false);gesture(GestureEngine.Action.VOLUME_UP);assertEquals(10,manager.getStreamVolume(AudioManager.STREAM_MUSIC));assertFalse(receiver.localControlAllowed());
    }
    @Test public void muteThenHeldDownAtZeroPreservesRestoreCheckpoint()throws Exception{
        gesture(GestureEngine.Action.MUTE);assertEquals(0,manager.getStreamVolume(AudioManager.STREAM_MUSIC));
        for(int i=0;i<10;i++)gesture(GestureEngine.Action.VOLUME_DOWN);
        assertEquals(0,manager.getStreamVolume(AudioManager.STREAM_MUSIC));assertEquals(10,field(service,"mutedVolume"));assertEquals(10L,field(service,"volumeBoundarySkips"));
        gesture(GestureEngine.Action.MUTE);assertEquals(10,manager.getStreamVolume(AudioManager.STREAM_MUSIC));
    }
    @Test public void staleAudioAndInvisibleSessionRejectLocalAdjustments()throws Exception{
        ShadowSystemClock.advanceBy(Duration.ofMillis(800));gesture(GestureEngine.Action.VOLUME_UP);assertEquals(10,manager.getStreamVolume(AudioManager.STREAM_MUSIC));
        ((LanFreshStream)field(receiver,"stream")).received(SystemClock.elapsedRealtime());assertTrue(receiver.fresh());settings.prefs.edit().putBoolean("screenOff",false).commit();LanAudioService.uiVisible=false;
        gesture(GestureEngine.Action.VOLUME_UP);assertEquals(10,manager.getStreamVolume(AudioManager.STREAM_MUSIC));
    }
}
