package com.vos.actuator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps track of whether VOS holds the assistant role (decision 45). On Tier S VOS cannot lock the
 * setting, so while the actuator is not the holder (another app, or none) a persistent notice says so
 * and links to the setup screen. Each change of state is audited once; repeated checks are quiet.
 */
final class RoleWatch {
    /** The Android side: the role check and the notice. */
    interface Platform {
        boolean holdsAssistantRole();

        void showNotice();

        void clearNotice();
    }

    /** Where role changes are audited. */
    interface Audit {
        void record(Map<String, Object> line);
    }

    private final Platform platform;
    private final Audit audit;
    private Boolean held; // null until the first check

    RoleWatch(Platform platform, Audit audit) {
        this.platform = platform;
        this.audit = audit;
    }

    /** Reads the role and updates the notice; {@code why} names what prompted the check, for the audit. */
    synchronized boolean check(String why) {
        boolean now;
        try {
            now = platform.holdsAssistantRole();
        } catch (RuntimeException e) {
            return held != null && held; // the role service is unavailable: keep the last state
        }
        if (now) {
            platform.clearNotice();
        } else {
            platform.showNotice();
        }
        if (held == null || held != now) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("event", "role.assistant");
            l.put("held", now);
            l.put("reason", why);
            audit.record(l);
        }
        held = now;
        return now;
    }
}
