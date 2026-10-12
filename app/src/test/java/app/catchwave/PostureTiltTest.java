package app.catchwave;

import org.junit.Test;
import static org.junit.Assert.*;

/** Coherent poses reproduce putting an upright, calibrated phone face-up on a table. */
public class PostureTiltTest {
    private static void move(PhysicalGestureTest.Rig r, double[] to) {
        double[] from = r.pose;
        double dot = 0;
        for (int i = 0; i < 4; i++) dot += from[i] * to[i];
        double sign = dot < 0 ? -1 : 1, angle = Math.acos(Math.min(1, Math.abs(dot)));
        r.gyro = 2 * angle;
        for (int step = 1; step <= 100; step++) {
            double f = step / 100.0,
                    a = Math.sin((1 - f) * angle) / Math.sin(angle),
                    b = Math.sin(f * angle) / Math.sin(angle);
            r.pose = new double[4];
            for (int i = 0; i < 4; i++) r.pose[i] = a * from[i] + b * sign * to[i];
            r.sample(0);
        }
        r.gyro = 0;
    }

    private static double[] faceUp(double yaw) {
        return new double[] {Math.cos(yaw / 2), 0, 0, Math.sin(yaw / 2)};
    }

    @Test
    public void layingFaceUpAtEitherHeadingDoesNotRepeatVolume() {
        for (int rotation = 0; rotation < 4; rotation++)
            for (double yaw : new double[] {-1.05, 1.05}) {
                PhysicalGestureTest.Rig r = new PhysicalGestureTest.Rig(rotation, Math.PI / 2);
                move(r, faceUp(yaw));
                r.quiet(4000);
                assertTrue(
                        "rotation=" + rotation + " yaw=" + yaw + " actions=" + r.actions,
                        r.actions.isEmpty());
                assertTrue(r.e.neutralReady(r.now));
            }
    }

    @Test
    public void postureChangeStopsExistingRepeatAndAllowsFreshTilt() {
        PhysicalGestureTest.Rig r = new PhysicalGestureTest.Rig(0, Math.PI / 2);
        r.move(0, -.55, 500);
        r.quiet(900);
        assertEquals(2, r.actions.size());
        int before = r.actions.size();
        move(r, faceUp(-1.05));
        r.quiet(4000);
        assertEquals(before, r.actions.size());
        assertTrue(r.dump().contains("postureRebases=1"));
        double[] table = faceUp(-1.05), axis = PhysicalGestureTest.axis(table, 0);
        move(r, PhysicalGestureTest.turn(table, axis, .55));
        r.quiet(1000);
        assertTrue(r.actions.size() > before);
        assertEquals(GestureEngine.Action.VOLUME_UP, r.actions.get(r.actions.size() - 1));
    }

    @Test
    public void returningUprightRebasesOnlyTiltWithoutBlockingOtherCommands() {
        PhysicalGestureTest.Rig r = new PhysicalGestureTest.Rig(0, Math.PI / 2);
        move(r, faceUp(1.05));
        r.quiet(1800);
        move(r, r.neutral);
        assertTrue(r.e.neutralReady(r.now));
        assertFalse(r.e.tiltAvailable(r.now));
        r.quiet(400);
        assertTrue(r.e.neutralReady(r.now));
        assertTrue(r.actions.isEmpty());
        r.move(0, .55, 500);
        r.quiet(1800);
        assertEquals(4, r.actions.size());
        assertTrue(r.actions.stream().allMatch(a -> a == GestureEngine.Action.VOLUME_UP));
    }

    @Test
    public void pickupKeepsTapCommandsAvailableDuringMovement() {
        PhysicalGestureTest.Rig r = new PhysicalGestureTest.Rig(0, 0);
        for (int i = 1; i <= 100; i++) {
            double angle = (Math.PI / 2) * i / 100;
            r.pose = PhysicalGestureTest.neutral(0, angle);
            r.gyro = Math.PI / 2;
            r.sample(0);
            assertTrue("pickup at sample " + i, r.e.neutralReady(r.now));
        }
        assertFalse(r.e.tiltAvailable(r.now));
        assertTrue(r.actions.isEmpty());
        r.gyro = .7;
        r.quiet(150);
        r.taps(3, 200);
        r.quiet(700);
        assertEquals(r.dump(), java.util.List.of(GestureEngine.Action.PLAY_PAUSE), r.actions);
        r.gyro = 0;
        r.quiet(400);
        assertTrue(r.e.tiltAvailable(r.now));
    }

    @Test
    public void faceDownReturnCannotRunVolumeToZero() {
        PhysicalGestureTest.Rig r = new PhysicalGestureTest.Rig(0, Math.PI / 2);
        move(r, new double[] {0, 1, 0, 0});
        r.quiet(20000);
        assertTrue(r.actions.isEmpty());
        assertTrue(r.e.neutralReady(r.now));
        assertTrue(r.dump().contains("flatTiltBlocked=true"));
    }
}
