package com.vos.session;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.function.Supplier;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES-256-GCM sealing for {@link KeyVault}. The key comes from a supplier: the Android Keystore in
 * production ({@link KeystoreKeys#vendorKeyWrapper()}), a software key in JVM tests. The cipher
 * picks a fresh random IV for every seal (Keystore keys refuse caller-chosen IVs).
 *
 * <p>Sealed layout: {@code [version=1][iv length][iv][ciphertext + 16-byte tag]}.
 */
public final class AesGcmSealer implements KeyVault.Sealer {
    private static final byte VERSION = 1;
    private static final String TRANSFORM = "AES/GCM/NoPadding";

    private final Supplier<SecretKey> key;

    public AesGcmSealer(Supplier<SecretKey> key) {
        this.key = key;
    }

    @Override
    public byte[] seal(byte[] plain, byte[] aad) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance(TRANSFORM);
        c.init(Cipher.ENCRYPT_MODE, key.get());
        c.updateAAD(aad);
        byte[] ct = c.doFinal(plain);
        byte[] iv = c.getIV();
        return ByteBuffer.allocate(2 + iv.length + ct.length).put(VERSION).put((byte) iv.length).put(iv).put(ct).array();
    }

    @Override
    public byte[] open(byte[] sealed, byte[] aad) throws GeneralSecurityException {
        if (sealed.length < 2 || sealed[0] != VERSION) {
            throw new GeneralSecurityException("unknown sealed format");
        }
        int ivLen = sealed[1] & 0xff;
        if (ivLen < 12 || sealed.length < 2 + ivLen + 16) {
            throw new GeneralSecurityException("truncated sealed data");
        }
        Cipher c = Cipher.getInstance(TRANSFORM);
        c.init(Cipher.DECRYPT_MODE, key.get(), new GCMParameterSpec(128, Arrays.copyOfRange(sealed, 2, 2 + ivLen)));
        c.updateAAD(aad);
        return c.doFinal(sealed, 2 + ivLen, sealed.length - 2 - ivLen);
    }
}
