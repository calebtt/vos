package com.vos.session;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Session egress allowlist (§5.8): exact host match only, no wildcards, empty by default.
 * The OkHttp interceptor and request log that use it arrive with M1-EGR-01.
 */
public final class EgressAllowlist {
    private final Set<String> hosts;

    public EgressAllowlist(Set<String> hosts) {
        Set<String> normalized = new HashSet<>();
        for (String host : hosts) {
            String h = normalize(host);
            if (h != null) {
                normalized.add(h);
            }
        }
        this.hosts = Collections.unmodifiableSet(normalized);
    }

    public boolean isAllowed(String host) {
        String h = normalize(host);
        return h != null && hosts.contains(h);
    }

    private static String normalize(String host) {
        if (host == null) {
            return null;
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        return h.isEmpty() || h.contains("*") ? null : h;
    }
}
