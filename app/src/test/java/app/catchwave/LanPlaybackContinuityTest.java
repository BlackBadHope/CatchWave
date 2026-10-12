package app.catchwave;

import android.media.*;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAudioTrack;
import static org.junit.Assert.*;

/** Runs the real PCM loop with a fake AudioTrack, not a physical speaker. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = LanPlaybackContinuityTest.Track.class)
public class LanPlaybackContinuityTest {
    @Implements(AudioTrack.class)
    public static class Track extends ShadowAudioTrack {
        static int plays, writes, flushes;
        static Runnable finish;

        @Implementation
        protected void play() {
            plays++;
        }

        @Implementation
        protected void pause() {}

        @Implementation
        protected void flush() {
            flushes++;
        }

        @Implementation
        protected int write(byte[] bytes, int offset, int count, int mode) {
            if (++writes == 2) finish.run();
            return count;
        }
    }

    static void field(Object o, String n, Object v) throws Exception {
        Field f = o.getClass().getDeclaredField(n);
        f.setAccessible(true);
        f.set(o, v);
    }

    static byte[] frame(long sequence) {
        return ByteBuffer.allocate(3856).putLong(sequence).putLong(sequence * 20).array();
    }

    void playGap(long next, int expectedPlays) throws Exception {
        Track.plays = Track.writes = Track.flushes = 0;
        LanSettings settings = new LanSettings(RuntimeEnvironment.getApplication());
        LanReceiver r =
                new LanReceiver(
                        settings,
                        new LanReceiver.Listener() {
                            public boolean ready() {
                                return true;
                            }

                            public void connected() {}

                            public void finished() {}

                            public void ack(boolean b) {}
                        });
        AudioTrack t =
                new AudioTrack.Builder()
                        .setAudioAttributes(
                                new AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_MEDIA)
                                        .build())
                        .setAudioFormat(
                                new AudioFormat.Builder()
                                        .setSampleRate(48000)
                                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                        .build())
                        .setBufferSizeInBytes(15360)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
        field(r, "track", t);
        long now = android.os.SystemClock.elapsedRealtime();
        LanTimeline timeline = new LanTimeline(0, now);
        timeline.arm(0, now);
        field(r, "timeline", timeline);
        field(r, "timelineArmed", true);
        Field queue = LanReceiver.class.getDeclaredField("audio");
        queue.setAccessible(true);
        ((ArrayBlockingQueue<byte[]>) queue.get(r)).offer(frame(next));
        Track.finish = r::close;
        Method playback = LanReceiver.class.getDeclaredMethod("playback", byte[].class);
        playback.setAccessible(true);
        try {
            playback.invoke(r, (Object) frame(0));
            assertEquals(2, Track.writes);
            assertEquals(expectedPlays, Track.plays);
            assertEquals(expectedPlays, Track.flushes);
        } finally {
            r.close();
            Track.finish = null;
        }
    }

    @Test
    public void oneMissingFrameDoesNotFlushAlreadyBufferedSound() throws Exception {
        playGap(2, 1);
    }

    @Test
    public void largeDiscontinuityStillRestartsOutput() throws Exception {
        playGap(20, 2);
    }
}
