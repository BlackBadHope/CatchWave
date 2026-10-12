package app.catchwave;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class HeldGestureTest {
    @Test
    public void disablingTiltControlStopsHeldStepsAndPreservesTapSeries() {
        PhysicalGestureTest.Rig r = new PhysicalGestureTest.Rig(0, Math.PI / 2);
        r.move(0, .55, 500);
        r.quiet(900);
        assertEquals(2, r.actions.size());
        r.e.tiltControl(false);
        r.quiet(1200);
        assertEquals(2, r.actions.size());
        r.move(.55, 0, 500);
        r.taps(3, 200);
        r.quiet(700);
        assertEquals(3, r.actions.size());
        assertEquals(GestureEngine.Action.PLAY_PAUSE, r.actions.get(2));
        r.e.tiltControl(true);
        r.move(0, -.55, 500);
        r.quiet(900);
        assertEquals(5, r.actions.size());
        assertEquals(GestureEngine.Action.VOLUME_DOWN, r.actions.get(4));
    }

    @Test
    public void missingOrientationAndDisabledSessionCannotRepeatHeldVolume() {
        PhysicalGestureTest.Rig r = new PhysicalGestureTest.Rig(0, Math.PI / 2);
        r.move(0, .55, 500);
        r.quiet(900);
        assertEquals(2, r.actions.size());
        double[] g = PhysicalGestureTest.gravity(r.pose);
        for (int i = 0; i < 100; i++) {
            r.now += 10;
            r.e.acceleration(r.now, g[0] * 9.81, g[1] * 9.81, g[2] * 9.81);
        }
        int atStale = r.actions.size();
        r.quiet(1700);
        assertEquals(atStale, r.actions.size());
        r.e.setEnabled(false, r.now);
        r.quiet(2000);
        assertEquals(atStale, r.actions.size());
    }

    @Test
    public void pendingTapSeriesInterruptsHeldTiltInsteadOfMixingCommands() {
        PhysicalGestureTest.Rig r = new PhysicalGestureTest.Rig(0, Math.PI / 2);
        r.move(0, .55, 500);
        r.quiet(900);
        assertEquals(2, r.actions.size());
        r.gyro = .7;
        r.taps(4, 200);
        r.quiet(700);
        assertEquals(3, r.actions.size());
        assertEquals(GestureEngine.Action.NEXT, r.actions.get(2));
    }
}
