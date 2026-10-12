package app.catchwave;

import org.junit.Test;
import static org.junit.Assert.*;

public class GestureArmingTest {
    final GestureArming gate = new GestureArming();
    long now;

    void sample(double degrees, boolean up, double gyro) {
        double a = Math.toRadians(degrees) / 2;
        gate.sample(
                now,
                up ? Math.cos(a) : 0,
                up ? 0 : Math.cos(a),
                up ? 0 : -Math.sin(a),
                up ? Math.sin(a) : 0,
                gyro,
                9.81);
        now += 50;
    }

    void hold(double angle, boolean up, int n) {
        for (int i = 0; i < n; i++) sample(angle, up, 0);
    }

    void arm() {
        gate.permit(true, now);
        sample(0, true, 0);
        assertTrue(gate.activate(now));
        assertTrue(gate.active(now));
    }

    @Test
    public void activeModeSurvivesOneMinute() {
        arm();
        hold(90, false, 1200);
        assertTrue(gate.active(now));
        assertTrue(gate.commands(now));
    }

    @Test
    public void viewingScreenAndReturningToTableDoNotDisarm() {
        arm();
        hold(90, true, 30);
        hold(0, false, 30);
        assertTrue(gate.active(now));
        assertTrue(gate.commands(now));
    }

    @Test
    public void explicitOffStaysOffThroughOldActivationGesture() {
        arm();
        gate.reset("MANUAL_OFF");
        hold(0, false, 19);
        hold(90, false, 30);
        assertFalse(gate.active(now));
        assertFalse(gate.commands(now));
    }

    @Test
    public void defaultLockedAndPermissionOff() {
        hold(0, false, 30);
        assertFalse(gate.activationReady());
        assertFalse(gate.activate(now));
        gate.permit(true, now);
        hold(0, false, 19);
        assertFalse(gate.active(now));
        gate.permit(false, now);
        assertFalse(gate.activationReady());
        assertFalse(gate.commands(now));
    }

    @Test
    public void neitherQuarterTurnCanArmWithoutButton() {
        for (int direction : new int[] {-1, 1}) {
            gate.reset("TEST");
            gate.permit(true, now);
            hold(0, false, 19);
            hold(direction * 90, false, 30);
            assertFalse(gate.active(now));
        }
    }

    @Test
    public void buttonWorksAtAnyHeadingAndPose() {
        for (double heading : new double[] {0, 75, 170, -150})
            for (boolean up : new boolean[] {true, false}) {
                gate.reset("TEST");
                gate.permit(true, now);
                sample(heading, up, 0);
                assertTrue(gate.activate(now));
                assertTrue(gate.commands(now));
            }
    }

    @Test
    public void repeatedButtonDoesNotRestartNeutral() {
        arm();
        long revision = gate.revision();
        assertFalse(gate.activate(now));
        assertEquals(revision, gate.revision());
    }

    @Test
    public void activationCannotSurviveSensorGapOrPermissionCycle() {
        arm();
        now += 151;
        assertFalse(gate.commands(now));
        hold(90, false, 30);
        assertFalse(gate.active(now));
        arm();
        gate.permit(false, now);
        gate.permit(true, now);
        hold(90, false, 30);
        assertFalse(gate.active(now));
    }

    @Test
    public void pickupDoesNotRestartTheEngineViaModeRevision() {
        arm();
        long revision = gate.revision();
        double a = Math.toRadians(50) / 2;
        gate.sample(now, Math.sin(a), Math.cos(a), 0, 0, .4, 9.81);
        now += 50;
        assertTrue(gate.active(now));
        assertEquals(revision, gate.revision());
        assertEquals("ARMED", gate.reason());
    }

    @Test
    public void repeatedPickupKeepsModeRevisionStable() {
        arm();
        long revision = gate.revision();
        double a = Math.toRadians(50) / 2;
        gate.sample(now, Math.sin(a), Math.cos(a), 0, 0, .4, 9.81);
        now += 50;
        hold(90, false, 5);
        gate.sample(now, Math.sin(a), Math.cos(a), 0, 0, .4, 9.81);
        now += 50;
        assertEquals(revision, gate.revision());
        assertTrue(gate.active(now));
    }

    @Test
    public void displayChangePreservesActivationAndInvalidatesOldCommands() {
        arm();
        long revision = gate.revision();
        gate.displayChange();
        assertTrue(gate.active(now));
        assertTrue(gate.revision() > revision);
        assertEquals("DISPLAY_NEUTRAL", gate.reason());
    }

    @Test
    public void invalidSensorFailsClosedAndDoesNotAutomaticallyRearm() {
        arm();
        gate.sample(now, Double.NaN, 0, 0, 0, 0, 9.81);
        assertFalse(gate.active(now));
        hold(0, false, 19);
        hold(90, false, 30);
        assertFalse(gate.active(now));
    }

    @Test
    public void staleSampleCannotEnableButton() {
        gate.permit(true, now);
        sample(0, true, 0);
        now += 151;
        assertFalse(gate.activate(now));
        assertFalse(gate.activationReady());
    }
}
