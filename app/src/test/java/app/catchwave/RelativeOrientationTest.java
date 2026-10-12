package app.catchwave;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class RelativeOrientationTest {
    @Test
    public void quaternionSignAndYawDoNotBecomeTilt() {
        RelativeOrientation o = new RelativeOrientation();
        assertTrue(o.sample(10, 1, 0, 0, 0, true));
        assertTrue(o.sample(20, -Math.cos(.3), 0, -Math.sin(.3), 0, false));
        assertEquals(.6, o.tilt(), 1e-9);
        o.sample(30, Math.cos(.3), 0, 0, Math.sin(.3), false);
        assertEquals(0, o.tilt(), 1e-9);
    }

    @Test
    public void arbitraryNeutralUsesRelativeQuaternion() {
        RelativeOrientation o = new RelativeOrientation();
        double a = .4, b = .3;
        o.sample(10, Math.cos(a), Math.sin(a), 0, 0, true);
        o.sample(
                20,
                Math.cos(a) * Math.cos(b),
                Math.sin(a) * Math.cos(b),
                Math.cos(a) * Math.sin(b) * Math.cos(2 * a)
                        + Math.sin(a) * Math.sin(b) * Math.sin(2 * a),
                Math.sin(a) * Math.sin(b) * Math.cos(2 * a)
                        - Math.cos(a) * Math.sin(b) * Math.sin(2 * a),
                false);
        assertEquals(.6, o.tilt(), 1e-9);
    }

    @Test
    public void allFourDisplayOrientationsMapRightToSameDirection() {
        for (int rotation = 0; rotation < 4; rotation++) {
            RelativeOrientation o = new RelativeOrientation();
            o.display(rotation);
            o.sample(10, 1, 0, 0, 0, true);
            double s = Math.sin(.3);
            o.sample(
                    20,
                    Math.cos(.3),
                    rotation == 1 ? -s : rotation == 3 ? s : 0,
                    rotation == 0 ? s : rotation == 2 ? -s : 0,
                    0,
                    false);
            assertEquals(.6, o.tilt(), 1e-9);
        }
    }

    @Test
    public void livePortraitToLandscapeRemapsAxisWithoutLosingCommands() {
        double[] portrait = PhysicalGestureTest.neutral(0, Math.PI / 2);
        RelativeOrientation o = new RelativeOrientation();
        PhysicalGestureTest.orientation(o, 10, portrait, true);
        double[] landscape =
                PhysicalGestureTest.turn(
                        portrait, PhysicalGestureTest.axis(portrait, 0), Math.PI / 2);
        PhysicalGestureTest.orientation(o, 20, landscape, false);
        o.display(1);
        assertTrue(o.fresh(20));
        assertTrue(o.tiltAvailable());
        assertEquals(0, o.tilt(), 1e-9);
        double[] rightTilt =
                PhysicalGestureTest.turn(landscape, PhysicalGestureTest.axis(landscape, 1), .55);
        PhysicalGestureTest.orientation(o, 30, rightTilt, false);
        assertEquals(.55, o.tilt(), 1e-9);
    }

    @Test
    public void staleBackwardsInvalidAndMissingSamplesInvalidateNeutral() {
        RelativeOrientation o = new RelativeOrientation();
        o.sample(10, 1, 0, 0, 0, true);
        assertTrue(o.fresh(160));
        assertFalse(o.fresh(161));
        assertFalse(o.sample(10, 1, 0, 0, 0, false));
        assertFalse(o.fresh(10));
        o.sample(20, 1, 0, 0, 0, true);
        assertFalse(o.sample(30, Double.NaN, 0, 0, 0, false));
        assertFalse(o.fresh(30));
        o.sample(40, 1, 0, 0, 0, true);
        assertFalse(o.sample(400, 1, 0, 0, 0, false));
        assertFalse(o.fresh(400));
        o.sample(410, 1, 0, 0, 0, true);
        o.reset();
        assertFalse(o.fresh(410));
    }

    @Test
    public void accelerationAloneCannotEmitTilt() {
        List<GestureEngine.Action> actions = new ArrayList<>();
        GestureEngine e = new GestureEngine(actions::add);
        e.setEnabled(true, 0);
        for (long t = 10; t <= 1800; t += 10) e.acceleration(t, 0, 0, 9.81);
        for (long t = 1810; t <= 4000; t += 10) e.acceleration(t, 5, 0, 8.4);
        assertTrue(actions.isEmpty());
    }

    @Test
    public void staleOrientationAndDisplayChangeCannotReuseTilt() {
        List<GestureEngine.Action> actions = new ArrayList<>();
        GestureEngine e = new GestureEngine(actions::add);
        e.setEnabled(true, 0);
        for (long t = 10; t <= 1800; t += 10) {
            e.orientation(t, 1, 0, 0, 0);
            e.acceleration(t, 0, 0, 9.81);
        }
        e.orientation(1810, Math.cos(.3), 0, Math.sin(.3), 0);
        for (long t = 1810; t <= 2300; t += 10) e.acceleration(t, 0, 0, 9.81);
        assertTrue(actions.isEmpty());
        e.displayRotation(1, 2300);
        for (long t = 2310; t <= 3900; t += 10) {
            e.orientation(t, Math.cos(.3), -Math.sin(.3), 0, 0);
            e.acceleration(t, 0, 0, 9.81);
        }
        assertTrue(actions.isEmpty());
    }
}
