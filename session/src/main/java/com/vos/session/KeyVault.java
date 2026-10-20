package com.vos.session;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * Vendor API keys, held by the session only (§5.4). Each key is sealed with AES-256-GCM by a
 * non-exportable Android Keystore key (StrongBox where present, {@link AesGcmSealer}) and stored as
 * one file per vendor in CE storage ({@code files/keys/<vendor>.bin}). The vendor id is the GCM
 * associated data, so a file copied to another vendor's name does not open. Values are never
 * logged; callers get them only through {@link #get(String)}.
 */
public final class KeyVault {
    /** Seals and opens bytes. The Keystore-backed {@link AesGcmSealer} in production. */
    public interface Sealer {
        byte[] seal(byte[] plain, byte[] aad) throws GeneralSecurityException;

        byte[] open(byte[] sealed, byte[] aad) throws GeneralSecurityException;
    }

    public static final String XAI = "xai";
    private static final Pattern VENDOR = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final int MAX_SECRET_CHARS = 4096;

    private final File dir;
    private final Sealer sealer;

    public KeyVault(File dir, Sealer sealer) {
        this.dir = dir;
        this.sealer = sealer;
    }

    public static boolean isVendorId(String vendor) {
        return vendor != null && VENDOR.matcher(vendor).matches();
    }

    /** Seals and stores {@code secret} (trimmed) for {@code vendor}, replacing any earlier key. */
    public synchronized void put(String vendor, String secret) throws IOException, GeneralSecurityException {
        requireVendor(vendor);
        String s = secret == null ? "" : secret.trim();
        if (s.isEmpty() || s.length() > MAX_SECRET_CHARS || s.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("not a usable key");
        }
        byte[] plain = s.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] sealed = sealer.seal(plain, aad(vendor));
            writeAtomically(file(vendor), sealed);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    /** The key for {@code vendor}, or null if none is stored. Throws if the file does not open. */
    public synchronized String get(String vendor) throws IOException, GeneralSecurityException {
        requireVendor(vendor);
        File f = file(vendor);
        if (!f.isFile()) {
            return null;
        }
        byte[] plain = sealer.open(Files.readAllBytes(f.toPath()), aad(vendor));
        try {
            return new String(plain, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    public synchronized boolean has(String vendor) {
        requireVendor(vendor);
        return file(vendor).isFile();
    }

    public synchronized boolean delete(String vendor) {
        requireVendor(vendor);
        return file(vendor).delete();
    }

    private File file(String vendor) {
        return new File(dir, vendor + ".bin");
    }

    private static byte[] aad(String vendor) {
        return ("vos.vendor-key:" + vendor).getBytes(StandardCharsets.UTF_8);
    }

    private static void requireVendor(String vendor) {
        if (!isVendorId(vendor)) {
            throw new IllegalArgumentException("bad vendor id");
        }
    }

    private void writeAtomically(File target, byte[] data) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        File tmp = new File(dir, target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        }
        if (!tmp.renameTo(target)) {
            tmp.delete();
            throw new IOException("cannot replace " + target.getName());
        }
    }
}
