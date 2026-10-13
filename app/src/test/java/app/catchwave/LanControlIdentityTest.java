// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import org.junit.Test;
import static org.junit.Assert.*;
public class LanControlIdentityTest {
    @Test public void signatureBindsSessionSequenceActionAndEpoch()throws Exception {
        var generator=KeyPairGenerator.getInstance("EC");generator.initialize(new ECGenParameterSpec("secp256r1"));var keys=generator.generateKeyPair();
        byte[] core=new byte[41];new SecureRandom().nextBytes(core);byte[] packet=LanControlIdentity.sign(keys.getPrivate(),core);
        var verifier=Signature.getInstance("SHA256withECDSA");verifier.initVerify(keys.getPublic());verifier.update(core);
        assertTrue(verifier.verify(packet,42,packet.length-42));assertEquals(packet.length-42,packet[41]&255);
        for(int offset:new int[]{0,16,24,32,33}){byte[] changed=core.clone();changed[offset]^=1;verifier.initVerify(keys.getPublic());verifier.update(changed);assertFalse(verifier.verify(packet,42,packet.length-42));}
    }
}
