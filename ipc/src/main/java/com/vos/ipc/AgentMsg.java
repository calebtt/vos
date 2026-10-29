package com.vos.ipc;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The one envelope the agent team's roles exchange (§3.4 "Agent ↔ agent", §3.7, decision 13):
 * {@code AgentMsg {from_role, to_role, goalId, turnId, kind, payload, origin}}. Session roles use it
 * in-process and session ↔ actuator roles cross IVosActuator / IVosSession with it, so both are
 * audited the same way ({@link #auditLine}); every cross-UID message is audited.
 *
 * <p>{@link #check} enforces decision 22's authority on the envelope itself: only the Conductor
 * sends a {@code goal}, a session role only proposes (a plan in a {@code result}), and an actuator
 * role never sends a plan in any message; {@code escalate {reason, wantClass}} goes to the Conductor, who decides; a veto
 * is an actuator role's {@code result} to the Conductor carrying {@code veto {reason, class, actId}}
 * with a reason from §9 Q23; {@code cancel} comes from the Conductor or an actuator role (the stop
 * fan-out). The payload is data with an {@code origin} tag (§5.6), never an instruction.
 */
public final class AgentMsg {
    /** §3.7's crew and the UID each role runs in. */
    public enum Role {
        CONDUCTOR(false), PLANNER(false), VISION(false), ROUTER(false),
        OPERATOR(true), DUPLEX(true), FILES(true), ANNOUNCER(true);

        public final boolean actuator;

        Role(boolean actuator) {
            this.actuator = actuator;
        }

        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        static Role of(String s) {
            for (Role r : values()) {
                if (r.wire().equals(s)) {
                    return r;
                }
            }
            return null;
        }
    }

    public enum Kind {
        GOAL, PROGRESS, RESULT, ESCALATE, CANCEL;

        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        static Kind of(String s) {
            for (Kind k : values()) {
                if (k.wire().equals(s)) {
                    return k;
                }
            }
            return null;
        }
    }

    /** §9 Q23: the closed set of typed veto reasons (decision 22). New reasons need a plan change. */
    public static final Set<String> VETO_REASONS = Set.of("needs_confirmation", "owner_absent", "mode_quiet",
            "mode_paused", "blocked_area", "secure_pixels", "permission_denied", "oracle_failed", "rate_limited",
            "scope_violation", "taint_flow");
    /** At most this many escalations per task (decision 26); the Conductor counts them. */
    public static final int MAX_ESCALATIONS = 2;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    /**
     * A §5.5 act class: a short token ({@code reversible}) or a dotted class ({@code comms.send},
     * {@code settings.security}). Never free text.
     */
    private static final Pattern ACT_CLASS = Pattern.compile("[a-z][a-z_]{0,31}(?:\\.[a-z][a-z_]{0,31}){0,3}");
    private static final int MAX_PAYLOAD_CHARS = 64 * 1024;

    public final Role from;
    public final Role to;
    public final String goalId;
    public final String turnId;
    public final Kind kind;
    public final JSONObject payload;
    public final String origin;

    public AgentMsg(Role from, Role to, String goalId, String turnId, Kind kind, JSONObject payload, String origin) {
        this.from = from;
        this.to = to;
        this.goalId = goalId;
        this.turnId = turnId;
        this.kind = kind;
        this.payload = payload == null ? new JSONObject() : payload;
        this.origin = origin;
    }

    /** Whether the message crosses the UID line (session ↔ actuator); those are always audited. */
    public boolean crossesUid() {
        return from.actuator != to.actuator;
    }

    /** Whether the message carries an actuator veto. */
    public boolean isVeto() {
        return kind == Kind.RESULT && payload.has("veto");
    }

    /** Null if the envelope is well formed and allowed by decision 22, else why it is refused. */
    public String check() {
        if (from == null || to == null || kind == null) {
            return "unknown role or kind";
        }
        if (from == to) {
            return "a role does not message itself";
        }
        if (goalId == null || !ID.matcher(goalId).matches() || turnId == null || !ID.matcher(turnId).matches()) {
            return "bad goalId or turnId";
        }
        if (origin == null || origin.isEmpty() || origin.length() > 128) {
            return "every payload needs an origin tag";
        }
        if (payload.toString().length() > MAX_PAYLOAD_CHARS) {
            return "payload too large";
        }
        if (from.actuator && payload.has("plan")) {
            return "an actuator role never substitutes its own plan"; // in any kind of message
        }
        switch (kind) {
            case GOAL:
                if (from != Role.CONDUCTOR) {
                    return "only the Conductor dispatches a goal; other roles propose in a result";
                }
                break;
            case ESCALATE:
                if (to != Role.CONDUCTOR) {
                    return "an escalation goes to the Conductor, who decides";
                }
                if (payload.optString("reason", "").isEmpty() || payload.optString("wantClass", "").isEmpty()) {
                    return "escalate needs {reason, wantClass}";
                }
                break;
            case CANCEL:
                if (from != Role.CONDUCTOR && !from.actuator) {
                    return "a cancel comes from the Conductor or the actuator";
                }
                break;
            case RESULT:
                if (payload.has("veto")) {
                    String bad = checkVeto(payload.optJSONObject("veto"));
                    if (bad != null) {
                        return bad;
                    }
                    if (!from.actuator || to != Role.CONDUCTOR) {
                        return "only an actuator role vetoes, and the veto goes to the Conductor";
                    }
                }
                break;
            default:
                break;
        }
        return null;
    }

    private static String checkVeto(JSONObject v) {
        if (v == null || v.length() != 3 || !VETO_REASONS.contains(v.optString("reason", null))
                || !ACT_CLASS.matcher(v.optString("class", "")).matches() || !ID.matcher(v.optString("actId", "")).matches()) {
            return "a veto is {reason, class, actId} with a reason from Q23";
        }
        return null;
    }

    /** The wire form (a Binder string argument or an in-process copy). */
    public String toJson() {
        try {
            return new JSONObject().put("from_role", from.wire()).put("to_role", to.wire()).put("goalId", goalId)
                    .put("turnId", turnId).put("kind", kind.wire()).put("payload", payload).put("origin", origin)
                    .toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Parses the wire form; unknown roles or kinds come back null, so {@link #check} refuses them. */
    public static AgentMsg fromJson(String json) {
        try {
            JSONObject o = new JSONObject(json);
            return new AgentMsg(Role.of(o.optString("from_role")), Role.of(o.optString("to_role")),
                    o.optString("goalId", null), o.optString("turnId", null), Kind.of(o.optString("kind")),
                    o.optJSONObject("payload"), o.optString("origin", null));
        } catch (JSONException e) {
            return null;
        }
    }

    /** The audit fields: who, to whom, which goal and turn, the kind and origin, never the payload text. */
    public Map<String, Object> auditLine(String verdict) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("event", "agent.msg");
        m.put("from_role", from == null ? null : from.wire());
        m.put("to_role", to == null ? null : to.wire());
        m.put("goalId", idOrInvalid(goalId));
        m.put("turnId", idOrInvalid(turnId));
        m.put("kind", kind == null ? null : kind.wire());
        m.put("origin", idOrInvalid(origin));
        m.put("cross_uid", from != null && to != null && crossesUid());
        if (isVeto()) {
            JSONObject v = payload.optJSONObject("veto");
            String reason = v == null ? null : v.optString("reason", null);
            if (reason != null && VETO_REASONS.contains(reason)) { // a Q23 reason, never screen text
                m.put("veto_reason", reason);
            }
            m.put("actId", idOrInvalid(v == null ? null : v.optString("actId", ""))); // an id, never screen text
        }
        m.put("payload_chars", payload.toString().length());
        m.put("verdict", verdict == null ? "ok" : verdict);
        return m;
    }

    /** An id token, or {@code (invalid)} so screen text cannot land in the audit line. */
    private static String idOrInvalid(String s) {
        return s != null && ID.matcher(s).matches() ? s : "(invalid)";
    }
}
