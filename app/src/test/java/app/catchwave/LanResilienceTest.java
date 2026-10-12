package app.catchwave;

import android.os.SystemClock;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

/** Real receive/gate/queue path on a fake clock; no socket or speaker. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class LanResilienceTest {
    private LanSettings settings;private LanReceiver receiver;private LanProtocol.AudioGate gate;
    private static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void field(Object o,String name,Object v)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,v);}
    private LanProtocol.Packet packet(long seq,long stamp){return new LanProtocol.Packet(LanProtocol.AUDIO,ByteBuffer.allocate(16+3840).putLong(seq).putLong(stamp).array());}
    @Before public void setup()throws Exception{
        ShadowSystemClock.advanceBy(Duration.ofMillis(2000));settings=new LanSettings(RuntimeEnvironment.getApplication());
        settings.prefs.edit().clear().putBoolean("explicitControlUi21",true).commit();settings.hostControl(true);
        receiver=new LanReceiver(settings,new LanReceiver.Listener(){public boolean ready(){return false;}public void connected(){}public void finished(){}public void ack(boolean a){}});
        byte[] body=new byte[24];ByteBuffer.wrap(body,16,8).putInt(48000).put((byte)2).put((byte)16).putShort((short)960);
        field(receiver,"format",new LanProtocol.Format(body));field(receiver,"latestSequence",0L);field(receiver,"lastAudio",SystemClock.elapsedRealtime());field(receiver,"outputState",2);
        ((LanFreshStream)field(receiver,"stream")).received(SystemClock.elapsedRealtime());
        LanTimeline timeline=new LanTimeline(0,SystemClock.elapsedRealtime());timeline.arm(0,SystemClock.elapsedRealtime());field(receiver,"timeline",timeline);field(receiver,"timelineArmed",true);
        receiver.policy(ByteBuffer.allocate(9).put((byte)1).putLong(0).array());gate=new LanProtocol.AudioGate();gate.accept(packet(0,0).body,3840);
    }
    @After public void cleanup(){receiver.close();settings.prefs.edit().clear().commit();}
    @Test public void shortPauseKeepsTransportButBlocksCommandsUntilFreshPacket()throws Exception{
        ShadowSystemClock.advanceBy(Duration.ofMillis(1000));long now=SystemClock.elapsedRealtime();
        assertFalse(receiver.receiveAudio(packet(1,20),gate,now));assertTrue(receiver.playbackAlive());assertFalse(receiver.fresh());assertEquals(0L,field(receiver,"latestSequence"));
        receiver.send(0);assertTrue(((ArrayBlockingQueue<?>)field(receiver,"controls")).isEmpty());
        assertTrue(receiver.receiveAudio(packet(2,1020),gate,now+20));ShadowSystemClock.advanceBy(Duration.ofMillis(20));assertTrue(receiver.fresh());
        receiver.send(0);assertEquals(1,((ArrayBlockingQueue<?>)field(receiver,"controls")).size());
    }
    @Test public void oldBacklogNeverReachesPcmQueueAndFreshFrameRecovers()throws Exception{
        ShadowSystemClock.advanceBy(Duration.ofMillis(1000));long now=SystemClock.elapsedRealtime();
        assertFalse(receiver.receiveAudio(packet(1,20),gate,now));assertFalse(receiver.receiveAudio(packet(2,40),gate,now));
        assertTrue(((ArrayBlockingQueue<?>)field(receiver,"audio")).isEmpty());assertEquals(2L,((AtomicLong)field(receiver,"lateDrops")).get());
        assertTrue(receiver.receiveAudio(packet(3,1000),gate,now));
        byte[] queued=(byte[])((ArrayBlockingQueue<?>)field(receiver,"audio")).poll();assertEquals(3,ByteBuffer.wrap(queued).getLong());assertTrue(receiver.playbackAlive());
    }
    @Test public void malformedDelayedPcmStillFailsProtocol()throws Exception{
        ShadowSystemClock.advanceBy(Duration.ofMillis(1000));
        try{receiver.receiveAudio(new LanProtocol.Packet(LanProtocol.AUDIO,new byte[16]),gate,SystemClock.elapsedRealtime());fail("Recovery must not bypass frame validation");}catch(java.io.IOException expected){}
        assertEquals(0,((AtomicLong)field(receiver,"lateDrops")).get());
    }
    @Test public void ownerCancellationCannotBeReactivatedByDelayedPacket()throws Exception{
        receiver.close();assertFalse(receiver.receiveAudio(packet(1,20),gate,SystemClock.elapsedRealtime()));assertFalse(receiver.playbackAlive());assertFalse(receiver.fresh());assertTrue(((ArrayBlockingQueue<?>)field(receiver,"audio")).isEmpty());
    }
    @Test public void graceIsBoundedAndStaleTrafficDoesNotRenewIt()throws Exception{
        ShadowSystemClock.advanceBy(Duration.ofMillis(4999));assertTrue(receiver.playbackAlive());assertFalse(receiver.receiveAudio(packet(1,20),gate,SystemClock.elapsedRealtime()));
        ShadowSystemClock.advanceBy(Duration.ofMillis(1));assertFalse(receiver.playbackAlive());assertFalse(receiver.fresh());
    }
    @Test public void gradualClockSkewDoesNotTurnLiveAudioIntoPermanentStaleTraffic()throws Exception{
        long start=SystemClock.elapsedRealtime();
        // 500 ppm difference over thirty minutes, without a network backlog.
        for(int seq=1;seq<=90000;seq++){
            long stamp=seq*20L,arrival=start+stamp+seq/100;
            assertTrue("fresh source frame "+seq,receiver.receiveAudio(packet(seq,stamp),gate,arrival));
        }
        assertEquals(90000L,field(receiver,"latestSequence"));assertEquals(0L,((AtomicLong)field(receiver,"lateDrops")).get());
    }
    @Test public void longSilenceCannotEarnClockCorrectionThatRevivesOldAudio()throws Exception{
        ShadowSystemClock.advanceBy(Duration.ofHours(2));
        assertFalse(receiver.receiveAudio(packet(1,20),gate,SystemClock.elapsedRealtime()));
        assertFalse(receiver.fresh());assertFalse(receiver.playbackAlive());assertEquals(0L,field(receiver,"latestSequence"));
        receiver.send(0);assertTrue(((ArrayBlockingQueue<?>)field(receiver,"controls")).isEmpty());
    }
}
