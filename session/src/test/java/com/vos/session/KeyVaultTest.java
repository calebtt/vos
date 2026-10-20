package com.vos.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.List;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class KeyVaultTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private SecretKey key;
    private File dir;
    private KeyVault vault;
    private final String secret = "xai-" + randomToken();

    private static String randomToken() {
        byte[] b = new byte[30];
        new SecureRandom().nextBytes(b);
        StringBuilder s = new StringBuilder();
        for (byte x : b) {
            s.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        }
        return s.toString();
    }

    @Before
    public void setUp() throws Exception {
        KeyGenerator g = KeyGenerator.getInstance("AES");
        g.init(256);
        key = g.generateKey();
        dir = tmp.newFolder("keys");
        vault = new KeyVault(dir, new AesGcmSealer(() -> key));
    }

    @Test
    public void roundTripsAndTrims() throws Exception {
        assertFalse(vault.has(KeyVault.XAI));
        assertNull(vault.get(KeyVault.XAI));
        vault.put(KeyVault.XAI, "  " + secret + "\n");
        assertTrue(vault.has(KeyVault.XAI));
        assertEquals(secret, vault.get(KeyVault.XAI));
    }

    @Test
    public void theFileHoldsNoPlaintext() throws Exception {
        vault.put(KeyVault.XAI, secret);
        byte[] onDisk = Files.readAllBytes(new File(dir, "xai.bin").toPath());
        String asText = new String(onDisk, StandardCharsets.ISO_8859_1);
        assertFalse(asText.contains(secret));
        assertFalse(asText.contains("xai-"));
        assertEquals(List.of("xai.bin"), List.of(dir.list()));
    }

    @Test
    public void eachSealUsesAFreshIv() throws Exception {
        vault.put(KeyVault.XAI, secret);
        byte[] first = Files.readAllBytes(new File(dir, "xai.bin").toPath());
        vault.put(KeyVault.XAI, secret);
        byte[] second = Files.readAllBytes(new File(dir, "xai.bin").toPath());
        assertFalse(java.util.Arrays.equals(first, second));
    }

    @Test
    public void aFileMovedToAnotherVendorDoesNotOpen() throws Exception {
        vault.put(KeyVault.XAI, secret);
        Files.copy(new File(dir, "xai.bin").toPath(), new File(dir, "openai.bin").toPath());
        assertThrows(GeneralSecurityException.class, () -> vault.get("openai"));
    }

    @Test
    public void aTamperedFileDoesNotOpen() throws Exception {
        vault.put(KeyVault.XAI, secret);
        File f = new File(dir, "xai.bin");
        byte[] b = Files.readAllBytes(f.toPath());
        b[b.length - 1] ^= 1;
        Files.write(f.toPath(), b);
        assertThrows(GeneralSecurityException.class, () -> vault.get(KeyVault.XAI));
    }

    @Test
    public void anotherWrappingKeyCannotOpenIt() throws Exception {
        vault.put(KeyVault.XAI, secret);
        KeyGenerator g = KeyGenerator.getInstance("AES");
        g.init(256);
        SecretKey other = g.generateKey();
        KeyVault stranger = new KeyVault(dir, new AesGcmSealer(() -> other));
        assertThrows(GeneralSecurityException.class, () -> stranger.get(KeyVault.XAI));
    }

    @Test
    public void rejectsBadVendorIdsAndUnusableKeys() {
        assertThrows(IllegalArgumentException.class, () -> vault.put("../x", secret));
        assertThrows(IllegalArgumentException.class, () -> vault.put("XAI", secret));
        assertThrows(IllegalArgumentException.class, () -> vault.put(KeyVault.XAI, "   "));
        assertThrows(IllegalArgumentException.class, () -> vault.put(KeyVault.XAI, "two words"));
    }

    @Test
    public void deleteRemovesTheKey() throws Exception {
        vault.put(KeyVault.XAI, secret);
        assertTrue(vault.delete(KeyVault.XAI));
        assertFalse(vault.has(KeyVault.XAI));
        assertNull(vault.get(KeyVault.XAI));
    }

    @Test
    public void importSealsPushedKeysAndDestroysTheFiles() throws Exception {
        File files = tmp.newFolder("files");
        File importDir = new File(files, KeyImport.DIR);
        assertTrue(importDir.mkdirs());
        Files.write(new File(importDir, "xai").toPath(), (secret + "\n").getBytes(StandardCharsets.UTF_8));
        Files.write(new File(importDir, "Bad Name").toPath(), "x".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(importDir, "empty").toPath(), new byte[0]);

        assertEquals(List.of("xai"), KeyImport.run(files, vault));
        assertEquals(secret, vault.get(KeyVault.XAI));
        assertFalse(importDir.exists());
        assertFalse(vault.has("empty"));
    }
}
