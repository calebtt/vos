package com.vos.session;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The {@code chat_text} backend (decision 21, §3.2 typed turns) on xAI chat completions, the same
 * vendor as {@code chat_voice}. A typed turn sees the shared conversation (decision 46) as messages,
 * then the owner's text; tool calls run in the actuator for this turn ({@link XaiRealtimeBackend.ToolCaller})
 * and their results go back to the model, for at most {@link #MAX_TOOL_ROUNDS} rounds. Each request
 * is one model call for the actuator's indicator ({@link ModelCalls}); once the turn's {@link TurnGate}
 * closes (its deadline), the call in flight is cancelled and no further one is sent. The key comes from the
 * {@link KeyVault} per call and goes only into the Authorization header; requests go through
 * {@link Egress}. Nothing here logs text.
 */
final class XaiTextBackend {
    static final int MAX_TOOL_ROUNDS = 4;
    static final long CALL_TIMEOUT_S = 30;
    private static final MediaType JSON = MediaType.get("application/json");

    private final Egress egress;
    private final KeyVault vault;
    private final ModelPolicy.Entry model;
    private final String instructions;
    private final JSONArray tools;

    XaiTextBackend(Egress egress, KeyVault vault, ModelPolicy.Entry model, String instructions, JSONArray realtimeTools) {
        this.egress = egress;
        this.vault = vault;
        this.model = model;
        this.instructions = instructions;
        this.tools = chatTools(realtimeTools);
    }

    /** The realtime tool schemas in chat-completions form; end_turn has no meaning for a typed turn. */
    static JSONArray chatTools(JSONArray realtime) {
        JSONArray out = new JSONArray();
        try {
            for (int i = 0; i < realtime.length(); i++) {
                JSONObject t = realtime.getJSONObject(i);
                if (XaiRealtimeBackend.END_TURN.equals(t.optString("name"))) {
                    continue;
                }
                JSONObject fn = new JSONObject().put("name", t.getString("name"))
                        .put("description", t.optString("description", ""))
                        .put("parameters", t.optJSONObject("parameters") == null ? new JSONObject() : t.getJSONObject("parameters"));
                out.put(new JSONObject().put("type", "function").put("function", fn));
            }
        } catch (JSONException e) {
            throw new IllegalArgumentException("bad tool schemas", e);
        }
        return out;
    }

    /** The shared conversation's seed items as chat messages (the same text and quoting as for voice). */
    static JSONArray messages(String instructions, List<JSONObject> seed, String typed) {
        JSONArray m = new JSONArray();
        try {
            m.put(new JSONObject().put("role", "system").put("content", instructions));
            for (JSONObject item : seed) {
                String text = item.getJSONArray("content").getJSONObject(0).getString("text");
                m.put(new JSONObject().put("role", item.getString("role")).put("content", text));
            }
            m.put(new JSONObject().put("role", "user").put("content", typed));
        } catch (JSONException e) {
            throw new IllegalArgumentException("bad seed", e);
        }
        return m;
    }

    /**
     * Answers {@code typed} for {@code turnId}; {@code toolLog} hears each tool name and result for the
     * shared conversation. Returns the reply text. Throws once {@code gate} has closed.
     */
    String reply(String turnId, List<JSONObject> seed, String typed, XaiRealtimeBackend.ToolCaller toolCaller,
            ModelCalls calls, ToolLog toolLog, TurnGate gate) throws IOException {
        String key;
        try {
            key = vault.get(KeyVault.XAI);
        } catch (GeneralSecurityException e) {
            throw new IOException("key unavailable", e);
        }
        if (key == null) {
            throw new IOException("no xai key");
        }
        JSONArray messages = messages(instructions, seed, typed);
        try {
            for (int round = 0; ; round++) {
                JSONObject body = new JSONObject().put("model", model.get("model")).put("messages", messages)
                        .put("max_tokens", 400);
                if (round < MAX_TOOL_ROUNDS && tools.length() > 0) {
                    body.put("tools", tools);
                }
                String callId = "t" + (round + 1);
                okhttp3.Call call = newCall(key, body.toString());
                if (!gate.track(call)) {
                    throw new IOException("typed turn over");
                }
                calls.started(turnId, callId);
                JSONObject msg;
                try {
                    msg = execute(call).getJSONArray("choices").getJSONObject(0).getJSONObject("message");
                } finally {
                    gate.untrack(call);
                    calls.ended(turnId, callId);
                }
                JSONArray toolCalls = msg.optJSONArray("tool_calls");
                if (toolCalls == null || toolCalls.length() == 0 || round >= MAX_TOOL_ROUNDS) {
                    String text = msg.isNull("content") ? "" : msg.optString("content", "").strip();
                    return text.isEmpty() ? "(no reply)" : text;
                }
                messages.put(new JSONObject().put("role", "assistant").put("content", msg.isNull("content")
                        ? JSONObject.NULL : msg.optString("content", "")).put("tool_calls", toolCalls));
                for (int i = 0; i < toolCalls.length(); i++) {
                    JSONObject tc = toolCalls.getJSONObject(i);
                    JSONObject fn = tc.getJSONObject("function");
                    String name = fn.getString("name");
                    String result = toolCaller.call(turnId, name, fn.isNull("arguments") ? "{}" : fn.optString("arguments", "{}"));
                    toolLog.tool(turnId, name, result);
                    messages.put(new JSONObject().put("role", "tool").put("tool_call_id", tc.getString("id"))
                            .put("content", result));
                }
            }
        } catch (JSONException e) {
            throw new IOException("bad chat reply", e);
        }
    }

    /** Hears each tool call's result (the shared conversation keeps one line of it). */
    interface ToolLog {
        void tool(String turnId, String name, String result);
    }

    private okhttp3.Call newCall(String key, String body) {
        Request req = new Request.Builder().url(model.get("url"))
                .header("Authorization", "Bearer " + key)
                .post(RequestBody.create(body, JSON))
                .build();
        okhttp3.Call call = egress.client().newCall(req);
        call.timeout().timeout(CALL_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS);
        return call;
    }

    private static JSONObject execute(okhttp3.Call call) throws IOException, JSONException {
        try (Response r = call.execute()) {
            ResponseBody rb = r.body();
            if (!r.isSuccessful() || rb == null) {
                throw new IOException("chat HTTP " + r.code());
            }
            return new JSONObject(rb.string());
        }
    }
}
