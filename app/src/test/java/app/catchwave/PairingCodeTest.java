package app.catchwave;

import org.junit.Test;
import static org.junit.Assert.*;

public class PairingCodeTest {
    private static final String ZERO = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    @Test
    public void fullStrengthFixtureAndCanonicalForm() {
        PairingCode p = PairingCode.parse("CW1/192.0.2.1/49372/" + ZERO + "/" + ZERO);
        assertEquals(32, p.token.length);
        assertEquals(32, p.pin.length);
        assertEquals(49372, p.port);
    }

    @Test
    public void rejectWrongFormatBoundsAndWeakenedSecrets() {
        for (String s :
                new String[] {
                    "CW1/192.0.2.1/49372/short/short",
                    "CW2/192.0.2.1/49372/" + ZERO + "/" + ZERO,
                    "CW1/256.1.1.1/49372/" + ZERO + "/" + ZERO,
                    "CW1/192.00.2.1/49372/" + ZERO + "/" + ZERO,
                    "CW1/0.0.0.0/49372/" + ZERO + "/" + ZERO,
                    "CW1/224.0.0.1/49372/" + ZERO + "/" + ZERO,
                    "CW1/192.0.2.1/65536/" + ZERO + "/" + ZERO,
                    "CW1/192.0.2.1/80/" + ZERO + "/" + ZERO,
                    "CW1/192.0.2.1/49372/" + ZERO + "/" + ZERO + "/extra",
                    " " + "CW1/192.0.2.1/49372/" + ZERO + "/" + ZERO
                }) assertThrows(IllegalArgumentException.class, () -> PairingCode.parse(s));
        assertThrows(IllegalArgumentException.class, () -> PairingCode.parse(null));
        assertThrows(IllegalArgumentException.class, () -> PairingCode.parse("x".repeat(257)));
    }
}
