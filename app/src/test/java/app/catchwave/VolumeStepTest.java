package app.catchwave;

import org.junit.Test;
import static org.junit.Assert.*;

public class VolumeStepTest {
    static final class Fake implements VolumeStep.Device {
        int volume = 20, calls;
        boolean fixed;

        public int current() {
            return volume;
        }

        public int min() {
            return 0;
        }

        public int max() {
            return 30;
        }

        public boolean fixed() {
            return fixed;
        }

        public void adjust(int direction) {
            calls++;
            volume = Math.max(0, Math.min(30, volume + 10 * direction));
        }
    }

    @Test
    public void heldStepsReachLimitsWithoutIssuingMoreAdjustments() {
        Fake f = new Fake();
        VolumeStep.Result r = VolumeStep.apply(f, 1);
        assertEquals(20, r.before);
        assertEquals(30, r.after);
        assertTrue(r.adjusted);
        for (int i = 0; i < 100; i++) assertFalse(VolumeStep.apply(f, 1).adjusted);
        assertEquals(1, f.calls);
        for (int i = 0; i < 3; i++) assertTrue(VolumeStep.apply(f, -1).adjusted);
        assertEquals(0, f.volume);
        for (int i = 0; i < 100; i++) assertFalse(VolumeStep.apply(f, -1).adjusted);
        assertEquals(4, f.calls);
        assertTrue(VolumeStep.apply(f, 1).adjusted);
        assertEquals(10, f.volume);
    }

    @Test
    public void fixedVolumeAndInvalidDirectionNeverInvokeAdjustment() {
        Fake f = new Fake();
        f.fixed = true;
        assertFalse(VolumeStep.apply(f, 1).adjusted);
        assertEquals(20, f.volume);
        assertEquals(0, f.calls);
        f.fixed = false;
        assertFalse(VolumeStep.apply(f, 0).adjusted);
        assertFalse(VolumeStep.apply(f, 2).adjusted);
        assertEquals(0, f.calls);
    }

    @Test
    public void externalVolumeChangesAndActualReadbackAreUsedForEachStep() {
        Fake f = new Fake();
        f.volume = 30;
        assertFalse(VolumeStep.apply(f, 1).adjusted);
        f.volume = 10;
        VolumeStep.Result r = VolumeStep.apply(f, 1);
        assertEquals(10, r.before);
        assertEquals(20, r.after);
        assertEquals(1, f.calls);
    }
}
