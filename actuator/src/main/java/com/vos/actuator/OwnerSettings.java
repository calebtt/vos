package com.vos.actuator;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The owner's settings, v0 (decision 19, P16, M1-CFG-01): a thin key/value store the actuator owns,
 * with a documented default per key and one audit line per change. It is a store, not a Settings
 * screen; the controls that write it (Settings, the mode QS tile, voice or typed commands) come with
 * the features they control. Kept in device-protected storage, so {@code agent_mode} is honored
 * before the first unlock (decision 20).
 *
 * <p>Only the keys in {@link #KEYS} exist. The security invariants (decision 19: the UID split, no
 * keys in the actuator, the trusted path, the egress allowlist, oracle over confession) are not keys,
 * so nothing here can turn one off; setting one is refused and audited like any unknown key.
 * Values are strings: {@code on}/{@code off} for switches, else one of the key's listed values.
 */
final class OwnerSettings {
    static final String ON = "on";
    static final String OFF = "off";

    /** One setting: its allowed values, the first being the default, and where the default comes from. */
    static final class Key {
        final String name;
        final List<String> values;
        final String source;

        Key(String name, String source, String... values) {
            this.name = name;
            this.values = List.of(values);
            this.source = source;
        }

        String defaultValue() {
            return values.get(0);
        }
    }

    static final Map<String, Key> KEYS = keys(
            new Key("wake_after_unlock", "decision 17: wake-word listening starts after a human unlock", ON, OFF),
            new Key("wake_locked", "decision 43: listening continues while locked, allowlist only", ON, OFF),
            new Key("wake_aec", "decision 18: playback cancelled from the wake-word stream", ON, OFF),
            new Key("long_press_fallback", "decision 17: an optional fallback, not the primary summon", OFF, ON),
            new Key("agent_mode", "decision 20: Full by default", "full", "quiet", "paused"),
            new Key("quiet_headset_speech", "decision 20: earbuds-only speech in Quiet, off by default", OFF, ON),
            new Key("patience", "decision 31, §9 Q33: standard end-of-turn by default", "standard", "long", "extra_long"));

    /** Decision 19's invariants by the names an owner or a command might use; refused, never stored. */
    static final Set<String> INVARIANTS = Set.of("uid_split", "actuator_keys", "trusted_path", "egress_allowlist",
            "oracle_over_confession");

    private final File file;
    private final Consumer<Map<String, Object>> audit;
    private final Map<String, String> values = new LinkedHashMap<>();

    /** Loads {@code file}; a missing, unreadable or malformed file, key or value means the default. */
    OwnerSettings(File file, Consumer<Map<String, Object>> audit) {
        this.file = file;
        this.audit = audit;
        JSONObject stored = null;
        try {
            if (file.isFile()) {
                stored = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            }
        } catch (IOException | JSONException e) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("event", "settings.unreadable");
            line.put("error", e.getClass().getSimpleName());
            audit.accept(line); // the defaults apply; the next change rewrites the file
        }
        for (Key k : KEYS.values()) {
            String v = stored == null ? null : stored.optString(k.name, null);
            values.put(k.name, v != null && k.values.contains(v) ? v : k.defaultValue());
        }
    }

    /** The current value of {@code key}; an unknown key throws (a typo is a bug, not a default). */
    synchronized String get(String key) {
        String v = values.get(key);
        if (v == null) {
            throw new IllegalArgumentException("no setting " + key);
        }
        return v;
    }

    /** Whether the switch {@code key} is on. */
    boolean on(String key) {
        return ON.equals(get(key));
    }

    /** Every key and its current value, in {@link #KEYS} order (the readback for an owner view or an eval). */
    synchronized Map<String, String> all() {
        return new LinkedHashMap<>(values);
    }

    /**
     * Sets {@code key} to {@code value} for {@code source} (e.g. "settings", "qs_tile", "voice",
     * "chat", "debug_shell"). Returns null if the value is set (or already was), else why not; a
     * change and every refusal write one audit line. The file is written before the value takes effect.
     */
    synchronized String set(String key, String value, String source) {
        Key k = key == null ? null : KEYS.get(key);
        String why = null;
        if (k == null) {
            why = key != null && INVARIANTS.contains(key) ? "a security invariant has no off switch" : "no such setting";
        } else if (value == null || !k.values.contains(value)) {
            why = "allowed: " + String.join(", ", k.values);
        }
        if (why != null) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("event", "setting.refused");
            line.put("key", clip(key));
            line.put("value", clip(value));
            line.put("source", clip(source));
            line.put("reason", why);
            audit.accept(line);
            return why;
        }
        String old = values.get(key);
        if (old.equals(value)) {
            return null;
        }
        Map<String, String> next = new LinkedHashMap<>(values);
        next.put(key, value);
        try {
            write(next);
        } catch (IOException e) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("event", "setting.refused");
            line.put("key", key);
            line.put("value", value);
            line.put("source", clip(source));
            line.put("reason", "not saved: " + e.getClass().getSimpleName());
            audit.accept(line);
            return "not saved";
        }
        values.put(key, value);
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("event", "setting.changed");
        line.put("key", key);
        line.put("from", old);
        line.put("to", value);
        line.put("source", clip(source));
        audit.accept(line);
        return null;
    }

    private void write(Map<String, String> next) throws IOException {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        File tmp = new File(dir, file.getName() + ".tmp");
        Files.write(tmp.toPath(), new JSONObject(next).toString().getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String clip(String s) {
        return s == null ? null : s.length() > 64 ? s.substring(0, 64) : s;
    }

    private static Map<String, Key> keys(Key... ks) {
        Map<String, Key> m = new LinkedHashMap<>();
        for (Key k : ks) {
            m.put(k.name, k);
        }
        return java.util.Collections.unmodifiableMap(m);
    }
}
