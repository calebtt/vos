package com.vos.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * ModelRouter v0 (§3.2.1): the checked-in registry and policy table ({@code res/raw/model_policy.json}).
 * For a task class it walks the policy's ordered list and returns the first entry that is available
 * (its key is present, it is not debug-only in a release build). A debug build may put one id first
 * with an override (eval/m1). {@code chat_voice} and {@code compact} (decision 46) are bound in this build.
 */
public final class ModelPolicy {
    /** One registry entry; {@code fields} keeps the whole JSON object for the backend's own settings. */
    public static final class Entry {
        public final String id;
        public final String kind;
        public final String vendor;
        public final boolean debugOnly;
        public final JSONObject fields;

        Entry(JSONObject o) throws JSONException {
            id = o.getString("id");
            kind = o.getString("kind");
            vendor = o.optString("vendor", null);
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
                    if (!registry.containsKey(id)) {
                        throw new IllegalArgumentException(taskClass + " names unknown model " + id);
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

    /**
     * The first available entry for {@code taskClass}, or null. {@code override} (debug builds only,
     * may be null) is tried first; debug-only entries are skipped unless {@code debugBuild}.
     */
    public Entry pick(String taskClass, String override, boolean debugBuild, Predicate<Entry> available) {
        List<String> order = new ArrayList<>();
        if (debugBuild && override != null && registry.containsKey(override)) {
            order.add(override);
        }
        order.addAll(candidates(taskClass));
        for (String id : order) {
            Entry e = registry.get(id);
            if ((!e.debugOnly || debugBuild) && available.test(e)) {
                return e;
            }
        }
        return null;
    }

    private static <T> Iterable<T> iterable(java.util.Iterator<T> it) {
        return () -> it;
    }
}
