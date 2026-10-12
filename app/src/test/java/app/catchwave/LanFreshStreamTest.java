package app.catchwave;

import org.junit.Test;
import static org.junit.Assert.*;

public class LanFreshStreamTest {
    @Test public void arrivalPublishedAfterCapturedNowCannotCauseFalseTimeout(){
        LanFreshStream stream=new LanFreshStream();long capturedNow=10000;
        stream.received(capturedNow+1);
        assertTrue("The racing arrival is a newer frame",stream.fresh(capturedNow));
        assertFalse("One millisecond scheduling race is not a five-second outage",stream.timedOut(capturedNow));
    }
    @Test public void realRecoveryDeadlineStillExpires(){
        LanFreshStream stream=new LanFreshStream();stream.received(10000);
        assertFalse(stream.timedOut(14999));assertTrue(stream.timedOut(15000));assertFalse(stream.fresh(10750));
    }
    @Test public void missingFirstFrameIsNotLive(){
        LanFreshStream stream=new LanFreshStream();assertFalse(stream.fresh(10000));assertTrue(stream.timedOut(10000));
    }
}
