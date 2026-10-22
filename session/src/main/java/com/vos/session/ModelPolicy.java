package com.vos.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * ModelRouter v0 (§3.2.1): the checked-in registry and policy table ({@code res/raw/model_policy.json}).
 * For a task class it walks the policy's ordered list and returns the first entry that is available
 * (its key is present, it is not debug-only in a release build). A debug build may put one id first
 * with an override (eval/m1). {@code chat_voice}, {@code chat_text} (decision 21) and {@code compact}
 * (decision 46) are bound in this build.
 * The actuator's task classes ({@link #ACTUATOR_CLASSES}) bind only to models bundled in the actuator
 * APK: a policy that points one elsewhere is rejected when it loads, and an override that does is
 * ignored (M1-MODEL-02; {@code ci/policy_check.py} checks the shipped policy the same way).
 */
public final class ModelPolicy {
    /**
     * The task classes the actuator runs (§3.2.1 security invariants). Only an on-device actuator
     * model may serve them: the actuator has no network, and the trusted path must not depend on a
     * vendor. Kept equal to {@code ci/actuator-model-classes.txt} (ModelPolicyTest checks).
     */
    public static final Set<String> ACTUATOR_CLASSES =
            Set.of("offline_grammar", "ocr_commit", "control_detect", "tts_house", "vad_aec", "wake");
    static final String ON_DEVICE_ACTUATOR = "on_device_actuator";

    /** One registry entry; {@code fields} keeps the whole JSON object for the backend's own settings. */
    public static final class Entry {
        public final String id;
        public final String kind;
        public final String vendor;
        public final String where; // on_device_actuator, on_device_session or cloud:<vendor>
        public final boolean debugOnly;
        public final JSONObject fields;

        Entry(JSONObject o) throws JSONException {
            id = o.getString("id");
            kind = o.getString("kind");
            vendor = o.optString("vendor", null);
            where = o.optString("where", null);
            debugOnly = o.optBoolean("debug_only", false);
            fields = o;
        }

        public String get(String key) {
            return fields.optString(key, null);
        }
    }

    private final Map<String, Entry> registry = new LinkedHashMap<>();
    private final Map<String, List<String>> policy = new LinkedHashMap<>();

    public ModelPolicy(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONArray reg = root.getJSONArray("registry");
            for (int i = 0; i < reg.length(); i++) {
                Entry e = new Entry(reg.getJSONObject(i));
                if (registry.put(e.id, e) != null) {
                    throw new IllegalArgumentException("duplicate model id " + e.id);
                }
            }
            JSONObject pol = root.getJSONObject("policy");
            for (String taskClass : iterable(pol.keys())) {
                JSONArray ids = pol.getJSONArray(taskClass);
                List<String> list = new ArrayList<>();
                for (int i = 0; i < ids.length(); i++) {
                    String id = ids.getString(i);
                    Entry e = registry.get(id);
                    if (e == null) {
                        throw new IllegalArgumentException(taskClass + " names unknown model " + id);
                    }
                    if (!allowed(taskClass, e)) {
                        throw new IllegalArgumentException(taskClass + " runs in the actuator; " + id + " is "
                                + e.where + ", not " + ON_DEVICE_ACTUATOR);
                    }
                    list.add(id);
                }
                policy.put(taskClass, Collections.unmodifiableList(list));
            }
        } catch (JSONException e) {
            throw new IllegalArgumentException("bad model policy", e);
        }
    }

    public List<String> candidates(String taskClass) {
        return policy.getOrDefault(taskClass, Collections.emptyList());
    }

    public Entry entry(String id) {
        return registry.get(id);
    }

    /** Whether {@code e} may serve {@code taskClass}: an actuator class needs an on-device actuator model. */
    public static boolean allowed(String taskClass, Entry e) {
        return !ACTUATOR_CLASSES.contains(taskClass) || ON_DEVICE_ACTUATOR.equals(e.where);
    }

    /**
     * The first available entry for {@code taskClass}, or null. {@code override} (debug builds only,
     * may be null) is tried first; debug-only entries are skipped unless {@code debugBuild}, and an
     * entry the class may not use ({@link #allowed}) is never returned, the override included.
     */
    public Entry pick(String taskClass, String override, boolean debugBuild, Predicate<Entry> available) {
        List<String> order = new ArrayList<>();
        if (debugBuild && override != null && registry.containsKey(override)) {
            order.add(override);
        }
        order.addAll(candidates(taskClass));
        for (String id : order) {
            Entry e = registry.get(id);
            if (allowed(taskClass, e) && (!e.debugOnly || debugBuild) && available.test(e)) {
                return e;
            }
        }
        return null;
    }

    private static <T> Iterable<T> iterable(java.util.Iterator<T> it) {
        return () -> it;
    }
}
