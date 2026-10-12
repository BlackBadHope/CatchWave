package app.catchwave;

import android.media.AudioTrack;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAudioTrack;
import static org.junit.Assert.*;

/** Real tone worker against the documented static-buffer lifecycle, with no speaker. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = ActivationTonePlaybackTest.StaticTrack.class)
public class ActivationTonePlaybackTest {
    @Implements(AudioTrack.class)
    public static class StaticTrack extends ShadowAudioTrack {
        static CountDownLatch released;
        static AtomicInteger writes, plays;
        static boolean shortWrite;
        private boolean loaded;

        @Implementation
        protected int getState() {
            return loaded ? AudioTrack.STATE_INITIALIZED : AudioTrack.STATE_NO_STATIC_DATA;
        }

        @Implementation
        protected int write(short[] samples, int offset, int length) {
            writes.incrementAndGet();
            loaded = !shortWrite;
            return shortWrite ? length - 1 : length;
        }

        @Implementation
        protected void play() {
            if (!loaded) throw new IllegalStateException();
            plays.incrementAndGet();
        }

        @Implementation
        protected void release() {
            released.countDown();
        }
    }

    @Before
    public void setup() {
        StaticTrack.released = new CountDownLatch(1);
        StaticTrack.writes = new AtomicInteger();
        StaticTrack.plays = new AtomicInteger();
        StaticTrack.shortWrite = false;
    }

    @Test
    public void validEmptyStaticTrackLoadsBeforePlaying() throws Exception {
        ActivationTone tone = new ActivationTone();
        try {
            tone.play(true);
            assertTrue(StaticTrack.released.await(2, TimeUnit.SECONDS));
            assertEquals(1, StaticTrack.writes.get());
            assertEquals(1, StaticTrack.plays.get());
        } finally {
            tone.close();
        }
    }

    @Test
    public void partialStaticWriteNeverPlaysAndReleasesTrack() throws Exception {
        StaticTrack.shortWrite = true;
        ActivationTone tone = new ActivationTone();
        try {
            tone.play(false);
            assertTrue(StaticTrack.released.await(2, TimeUnit.SECONDS));
            assertEquals(0, StaticTrack.plays.get());
        } finally {
            tone.close();
        }
    }
}
