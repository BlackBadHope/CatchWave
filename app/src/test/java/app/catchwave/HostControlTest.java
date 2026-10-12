package app.catchwave;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import java.nio.ByteBuffer;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class HostControlTest {
    @Test
    public void offThenOnCannotReviveQueuedCommands() {
        LanSettings s = new LanSettings(RuntimeEnvironment.getApplication());
        s.prefs.edit().clear().commit();
        LanReceiver r =
                new LanReceiver(
                        s,
                        new LanReceiver.Listener() {
                            public boolean ready() {
                                return false;
                            }

                            public void connected() {}

                            public void finished() {}

                            public void ack(boolean a) {}
                        });
        assertFalse(r.commandAllowed(0));
        s.hostControl(true);
        long epoch = s.controlEpoch();
        assertTrue(r.commandAllowed(epoch));
        s.hostControl(false);
        assertFalse(r.commandAllowed(epoch));
        s.hostControl(true);
        assertFalse(r.commandAllowed(epoch));
        assertTrue(r.commandAllowed(s.controlEpoch()));
        r.close();
        s.prefs.edit().clear().commit();
    }

    @Test
    public void pcOffThenOnInvalidatesEvenDequeuedGeneration() throws Exception {
        LanSettings s = new LanSettings(RuntimeEnvironment.getApplication());
        s.prefs.edit().clear().commit();
        s.hostControl(true);
        LanReceiver r =
                new LanReceiver(
                        s,
                        new LanReceiver.Listener() {
                            public boolean ready() {
                                return false;
                            }

                            public void connected() {}

                            public void finished() {}

                            public void ack(boolean a) {}
                        });
        r.policy(ByteBuffer.allocate(9).put((byte) 1).putLong(0).array());
        long generation = r.policyGeneration();
        assertTrue(r.commandAllowed(s.controlEpoch(), generation));
        r.policy(ByteBuffer.allocate(9).put((byte) 0).putLong(1).array());
        r.policy(ByteBuffer.allocate(9).put((byte) 1).putLong(2).array());
        assertFalse(r.commandAllowed(s.controlEpoch(), generation));
        assertTrue(r.commandAllowed(s.controlEpoch(), 2));
        r.close();
    }
}
