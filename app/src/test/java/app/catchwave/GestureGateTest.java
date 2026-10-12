package app.catchwave;

import org.junit.Test;
import static org.junit.Assert.*;

public class GestureGateTest {
    @Test
    public void disconnectedDisabledOrInvisibleNeverListen() {
        assertFalse(GestureGate.visibleSession(false, true, true, true));
        assertFalse(GestureGate.visibleSession(true, false, true, true));
        assertFalse(GestureGate.visibleSession(true, true, false, false));
        assertTrue(GestureGate.visibleSession(true, true, true, false));
        assertTrue(GestureGate.visibleSession(true, true, false, true));
    }

    @Test
    public void queuedSamplesDoNotBecomeFreshCommands() {
        assertTrue(GestureGate.freshSample(1000, 950));
        assertFalse(GestureGate.freshSample(1000, 500));
        assertFalse(GestureGate.freshSample(1000, 1001));
        assertFalse(GestureGate.freshSample(1000, -1));
    }
}
