package app.catchwave;
import android.os.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=35) @LooperMode(LooperMode.Mode.PAUSED)
public class LanFocusTest {
    @Test public void delayedMainFocusCallbackAcceptsContinuallyReceivedFrames(){
        LanFreshStream stream=new LanFreshStream();stream.received(SystemClock.elapsedRealtime());AtomicInteger calls=new AtomicInteger();
        var result=LanFocus.request(new Handler(Looper.getMainLooper()),()->true,()->stream.fresh(SystemClock.elapsedRealtime()),()->{calls.incrementAndGet();return true;});
        for(int i=0;i<40;i++){ShadowSystemClock.advanceBy(Duration.ofMillis(20));stream.received(SystemClock.elapsedRealtime());}
        assertFalse(result.isDone());Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(result.join());assertEquals(1,calls.get());
    }
    @Test public void delayedMainCallbackRejectsStaleStreamWithoutTakingFocus(){
        LanFreshStream stream=new LanFreshStream();stream.received(SystemClock.elapsedRealtime());AtomicInteger calls=new AtomicInteger();
        var result=LanFocus.request(new Handler(Looper.getMainLooper()),()->true,()->stream.fresh(SystemClock.elapsedRealtime()),()->{calls.incrementAndGet();return true;});
        ShadowSystemClock.advanceBy(Duration.ofMillis(800));Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse(result.join());assertEquals(0,calls.get());
    }
    @Test public void androidFocusRuntimeFailureCompletesFutureWithoutCrashingMain(){
        var result=LanFocus.request(new Handler(Looper.getMainLooper()),()->true,()->true,()->{throw new IllegalStateException("inert test payload");});
        Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse(result.join());
    }
}
