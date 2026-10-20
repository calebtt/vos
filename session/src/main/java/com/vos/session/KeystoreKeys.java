package com.vos.session;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;
import android.security.keystore.StrongBoxUnavailableException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;

/**
 * The Android Keystore key that wraps vendor keys (§5.4): AES-256-GCM, non-exportable, StrongBox
 * where the device has it, else the TEE. Not bound to user authentication or an unlocked device,
 * because pocket use happens while the phone is locked.
 */
final class KeystoreKeys {
    static final String ALIAS = "vos.vendor-keys.v1";
    private static final String STORE = "AndroidKeyStore";

    private KeystoreKeys() {
    }

    /** Loads the wrapping key, creating it on first use. */
    static synchronized SecretKey vendorKeyWrapper() {
        try {
            KeyStore ks = KeyStore.getInstance(STORE);
            ks.load(null);
            if (ks.containsAlias(ALIAS)) {
                return (SecretKey) ks.getKey(ALIAS, null);
            }
            try {
                return generate(true);
            } catch (StrongBoxUnavailableException e) {
                return generate(false);
            }
        } catch (GeneralSecurityException | java.io.IOException e) {
            throw new IllegalStateException("vendor key wrapper unavailable", e);
        }
    }

    /** "strongbox", "tee" or "software", for the one-time startup log line. */
    static String securityLevel(SecretKey key) {
        try {
            KeyInfo info = (KeyInfo) SecretKeyFactory.getInstance(key.getAlgorithm(), STORE).getKeySpec(key, KeyInfo.class);
            switch (info.getSecurityLevel()) {
                case KeyProperties.SECURITY_LEVEL_STRONGBOX:
                    return "strongbox";
                case KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT:
                    return "tee";
                default:
                    return "software";
            }
        } catch (GeneralSecurityException e) {
            return "unknown";
        }
    }

    private static SecretKey generate(boolean strongBox) throws GeneralSecurityException {
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE);
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setIsStrongBoxBacked(strongBox)
                .build());
        return g.generateKey();
    }
}
