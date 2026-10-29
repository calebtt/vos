package com.vos.actuator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The chat window's thread (decision 21). {@code recentThread} is the shared conversation, so a
 * reload replaces what the window was showing. Lines the session does not have yet — the open typed
 * turn's owner line and reply, and a trailing actuator note — stay.
 */
final class ChatThread {
    private ChatThread() {
    }

    /** One line as the window shows it. */
    static final class Line {
        final String role; // "owner", "vos", or "note" (the actuator's own status text)
        final String text;

        Line(String role, String text) {
            this.role = role;
            this.text = text;
        }
    }

    /**
     * {@code current} is what the window shows, {@code fresh} is the session's recentThread
     * (oldest first). While {@code typedOpen}, a trailing owner or VOS line the fresh thread does
     * not yet contain is kept after it, and so is a trailing note.
     */
    static List<Line> merge(List<Line> current, List<Line> fresh, boolean typedOpen) {
        int end = current.size();
        while (end > 0 && "note".equals(current.get(end - 1).role)) {
            end--;
        }
        List<Line> out = new ArrayList<>(fresh);
        if (typedOpen) {
            out.addAll(pending(current.subList(0, end), fresh));
        }
        out.addAll(current.subList(end, current.size()));
        return out;
    }

    /** The trailing owner/VOS lines of {@code body} that {@code fresh} has no remaining copy of. */
    private static List<Line> pending(List<Line> body, List<Line> fresh) {
        Map<String, Integer> pool = new HashMap<>();
        for (Line l : fresh) {
            pool.merge(key(l), 1, Integer::sum);
        }
        List<Line> suffix = new ArrayList<>();
        for (Line l : body) {
            String k = key(l);
            int n = pool.getOrDefault(k, 0);
            if (n > 0) {
                pool.put(k, n - 1);
                suffix.clear();
            } else if ("owner".equals(l.role) || "vos".equals(l.role)) {
                suffix.add(l);
            } else {
                suffix.clear(); // a note or anything else in the middle is not the open turn
            }
        }
        return suffix;
    }

    private static String key(Line l) {
        return l.role + "\0" + l.text;
    }
}
