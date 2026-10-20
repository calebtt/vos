package com.vos.session;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Debug builds only: imports keys pushed into {@code files/key-import/<vendor>} with
 * {@code adb shell run-as com.vos.session} (eval/README.md), so a key never travels on a command
 * line or through logcat. Each file is sealed into the {@link KeyVault}, overwritten with zeros
 * and deleted. Release builds take keys only through the paste field (QR entry comes later).
 */
final class KeyImport {
    static final String DIR = "key-import";
    private static final long MAX_BYTES = 4096;

    private KeyImport() {
    }

    /**
     * Returns the vendor ids imported. A file that cannot be imported, for any reason (a Keystore
     * failure included), is still destroyed and the loop goes on to the next, and the directory is
     * removed afterwards (the shell creates it with its own, looser mode).
     */
    static List<String> run(File filesDir, KeyVault vault) {
        List<String> imported = new ArrayList<>();
        File dir = new File(filesDir, DIR);
        File[] files = dir.listFiles();
        if (files == null) {
            return imported;
        }
        Arrays.sort(files);
        try {
            for (File f : files) {
                String vendor = f.getName();
                try {
                    if (KeyVault.isVendorId(vendor) && f.isFile() && f.length() <= MAX_BYTES) {
                        byte[] raw = Files.readAllBytes(f.toPath());
                        try {
                            vault.put(vendor, new String(raw, StandardCharsets.UTF_8));
                            imported.add(vendor);
                        } finally {
                            Arrays.fill(raw, (byte) 0);
                        }
                    }
                } catch (Exception e) {
                    // Not imported (bad name or value, I/O, or a Keystore failure, which surfaces as a
                    // RuntimeException); the file is destroyed either way and the next one is tried.
                } finally {
                    destroy(f);
                }
            }
        } finally {
            dir.delete();
        }
        return imported;
    }

    /** Overwrites the file in place (no truncation first, which would free the old blocks) and deletes it. */
    private static void destroy(File f) {
        if (f.isFile()) {
            try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
                raf.write(new byte[(int) Math.min(raf.length(), MAX_BYTES)]);
                raf.getFD().sync();
            } catch (IOException ignored) {
                // still deleted below
            }
        }
        f.delete();
    }
}
