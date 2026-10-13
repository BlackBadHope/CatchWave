// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.nio.ByteBuffer;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;

/** Non-exportable, app-private control key. Signing has no exported IPC entry point. */
final class LanControlIdentity {
    private static final String ALIAS="CatchWave-control-v1";
    private static final Object STORE_LOCK=new Object();
    private final PrivateKey key;private final byte[] publicKey;
    LanControlIdentity()throws GeneralSecurityException,java.io.IOException {
        synchronized(STORE_LOCK){
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(ALIAS)){
            KeyPairGenerator generator=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build());
            generator.generateKeyPair();
        }
        key=(PrivateKey)store.getKey(ALIAS,null);publicKey=store.getCertificate(ALIAS).getPublicKey().getEncoded();
        }
    }
    byte[] authenticate(byte[] token){byte[] body=Arrays.copyOf(token,35+publicKey.length);body[32]=1;ByteBuffer.wrap(body,33,2).putShort((short)publicKey.length);System.arraycopy(publicKey,0,body,35,publicKey.length);return body;}
    static byte[] sign(PrivateKey key,byte[] core)throws GeneralSecurityException {
        if(core.length!=41)throw new GeneralSecurityException("Invalid command");
        Signature signer=Signature.getInstance("SHA256withECDSA");signer.initSign(key);signer.update(core);byte[] signature=signer.sign();
        if(signature.length>72)throw new GeneralSecurityException("Invalid signature");
        byte[] body=Arrays.copyOf(core,42+signature.length);body[41]=(byte)signature.length;System.arraycopy(signature,0,body,42,signature.length);return body;
    }
    byte[] sign(byte[] core)throws GeneralSecurityException{return sign(key,core);}
    String fingerprint()throws GeneralSecurityException {byte[] digest=MessageDigest.getInstance("SHA-256").digest(publicKey);StringBuilder text=new StringBuilder();for(byte b:digest)text.append(String.format(java.util.Locale.ROOT,"%02X",b&255));return text.toString();}
}
