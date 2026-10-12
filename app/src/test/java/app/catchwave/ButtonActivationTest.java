package app.catchwave;

import org.junit.Test;
import static org.junit.Assert.*;

/** Owner-facing policy: only the button arms; table position is not a prerequisite. */
public class ButtonActivationTest {
    @Test
    public void buttonWorksWithoutFirstPuttingPhoneFaceDown() {
        GestureArming gate = new GestureArming();
        gate.permit(true, 1000);
        gate.sample(1000, 1, 0, 0, 0, 0, 9.81);
        assertTrue(gate.activate(1000));
        assertTrue(gate.active(1000));
    }

    @Test
    public void quarterTurnNeverActivatesWithoutButton() {
        GestureArming gate = new GestureArming();
        gate.permit(true, 1000);
        for (long t = 1000; t <= 2000; t += 50) gate.sample(t, 0, 1, 0, 0, 0, 9.81);
        double c = Math.sqrt(.5);
        for (long t = 2050; t <= 2500; t += 50) gate.sample(t, 0, c, -c, 0, 0, 9.81);
        assertFalse(gate.active(2500));
        assertFalse(gate.commands(2500));
    }
}
