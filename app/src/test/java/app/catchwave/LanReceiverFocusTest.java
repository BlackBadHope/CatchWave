package app.catchwave;

import android.media.*;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35) @LooperMode(LooperMode.Mode.PAUSED)
public class LanReceiverFocusTest {
    private LanReceiver receiver;private AudioTrack track;
    private static Object field(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private static void field(Object owner,String name,Object value)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}
    @Before public void setup()throws Exception{
        LanSettings settings=new LanSettings(RuntimeEnvironment.getApplication());
        receiver=new LanReceiver(settings,new LanReceiver.Listener(){public boolean ready(){return true;}public void connected(){}public void finished(){}public void ack(boolean accepted){}});
        track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(15360).build();
        track.play();field(receiver,"track",track);
    }
    @After public void cleanup()throws Exception{receiver.close();field(receiver,"track",null);track.release();}
    @Test public void losingFocusImmediatelyPausesOutputAndClearsQueuedAudioButKeepsReceiverOpen()throws Exception{
        @SuppressWarnings("unchecked") ArrayBlockingQueue<byte[]> audio=(ArrayBlockingQueue<byte[]>)field(receiver,"audio");audio.offer(new byte[16]);
        receiver.focus(AudioManager.AUDIOFOCUS_LOSS);
        assertEquals(AudioTrack.PLAYSTATE_PAUSED,track.getPlayState());assertTrue(receiver.focusPaused());assertTrue(audio.isEmpty());
        assertTrue(((AtomicBoolean)field(receiver,"active")).get());
    }
    @Test public void gainDoesNotPlayBufferedHistoryBeforeFreshFrameArrives(){
        receiver.focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);receiver.focus(AudioManager.AUDIOFOCUS_GAIN);
        assertFalse(receiver.focusPaused());assertEquals("Playback worker must resume only with a fresh frame",AudioTrack.PLAYSTATE_PAUSED,track.getPlayState());
    }
    @Test public void writeBornBeforeFocusLossCannotContinueAfterGain()throws Exception{
        long oldEpoch=(long)field(receiver,"outputEpoch");receiver.focus(AudioManager.AUDIOFOCUS_LOSS);receiver.focus(AudioManager.AUDIOFOCUS_GAIN);
        Method write=LanReceiver.class.getDeclaredMethod("writePcm",AudioTrack.class,byte[].class,long.class);write.setAccessible(true);
        assertEquals(Boolean.FALSE,write.invoke(receiver,track,new byte[3840],oldEpoch));
    }
    @Test public void notificationKeepsAudioAndQueuePlayingWithoutChangingOutputEpoch()throws Exception{
        @SuppressWarnings("unchecked") ArrayBlockingQueue<byte[]> audio=(ArrayBlockingQueue<byte[]>)field(receiver,"audio");audio.offer(new byte[16]);
        long epoch=(long)field(receiver,"outputEpoch");receiver.focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
        assertFalse(receiver.focusPaused());assertEquals(AudioTrack.PLAYSTATE_PLAYING,track.getPlayState());assertEquals(1,audio.size());assertEquals(epoch,field(receiver,"outputEpoch"));
        assertTrue((boolean)field(receiver,"focusDucked"));receiver.focus(AudioManager.AUDIOFOCUS_GAIN);
        assertEquals(AudioTrack.PLAYSTATE_PLAYING,track.getPlayState());assertFalse((boolean)field(receiver,"focusDucked"));
    }
    @Test public void duckAndGainPreserveOwnerVolumeAndMute()throws Exception{
        receiver.volume(0.35f);receiver.focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);receiver.focus(AudioManager.AUDIOFOCUS_GAIN);
        assertEquals(0.35f,(float)field(receiver,"requestedVolume"),0f);
        receiver.volume(0f);receiver.focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);receiver.focus(AudioManager.AUDIOFOCUS_GAIN);
        assertEquals(0f,(float)field(receiver,"requestedVolume"),0f);
    }
    @Test public void notificationDoesNotOverrideFullPhoneMediaPause()throws Exception{
        receiver.focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);receiver.focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
        assertTrue(receiver.focusPaused());assertEquals(AudioTrack.PLAYSTATE_PAUSED,track.getPlayState());assertFalse((boolean)field(receiver,"focusDucked"));
    }
}
