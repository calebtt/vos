package com.vos.ipc;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Grant set per caller package (§5.3 step 4). Immutable. */
public final class GrantTable {
    private final Map<String, Set<Grant>> byPackage;

    private GrantTable(Map<String, Set<Grant>> byPackage) {
        this.byPackage = byPackage;
    }

    public static GrantTable of(String packageName, Grant first, Grant... rest) {
        Map<String, Set<Grant>> m = new HashMap<>();
        m.put(packageName, Collections.unmodifiableSet(EnumSet.of(first, rest)));
        return new GrantTable(Collections.unmodifiableMap(m));
    }

    /** The grants for {@code packageName}; empty if it has none. */
    public Set<Grant> forPackage(String packageName) {
        Set<Grant> g = byPackage.get(packageName);
        return g != null ? g : Collections.emptySet();
    }
}
