package com.vos.session;

import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The xAI realtime voice protocol (wss://api.x.ai/v1/realtime), as observed 2026-10-20: OpenAI
 * Realtime-shaped, with its own event names. Builds the messages the session sends and dispatches
 * the events it receives to a {@link Handler}. No I/O here, so it is tested on the JVM.
 */
final class XaiRealtime {
    /** Events the voice backend acts on; everything else is ignored. */
    interface Handler {
        void sessionCreated(String servedModel);

        void sessionUpdated();

        void speechStarted();

        /** The vendor opened a response, before any of its audio. */
        void responseCreated(String responseId);

        void speechStopped();

        void ownerTranscript(String text);

        /** Reply audio; {@code responseId} names the response it belongs to (barge-in drops by it). */
        void audio(String responseId, byte[] pcm);

        /** Part of a reply's transcript, as it is generated (for the heard note, decision 48). */
        void replyTranscriptDelta(String responseId, String delta);

        /** A reply's whole transcript; {@code responseId} is "" when the vendor sends none. */
        void replyTranscript(String responseId, String text);

        /** A response finished; {@code status} is e.g. "completed" or "cancelled" (the owner talked over it). */
        void responseDone(String responseId, String status);

        /** The model calls a tool; {@code argsJson} is the model's argument object as text. */
        /** A complete tool call; {@code responseId} names the response it belongs to. */
        void functionCall(String responseId, String callId, String name, String argsJson);

        /** {@code eventId} names the client event the vendor refused, "" when it names none. */
        void error(String code, String message, String eventId);
    }

    private XaiRealtime() {
    }

    /** Configures the session: instructions, voice, server VAD, PCM at {@code rate} both ways, no tools. */
    static String sessionUpdate(String instructions, String voice, int rate) {
        return sessionUpdate(instructions, voice, rate, new JSONArray());
    }

    /** As above, with the function tools the model may call (vendor format: type, name, description, parameters). */
    static String sessionUpdate(String instructions, String voice, int rate, JSONArray tools) {
        return sessionUpdate(instructions, voice, rate, tools, TurnDetection.VENDOR_DEFAULT);
    }

    /** As above, with the server VAD's end-of-turn settings; a null value is left to the vendor. */
    static String sessionUpdate(String instructions, String voice, int rate, JSONArray tools, TurnDetection td) {
        try {
            JSONObject format = new JSONObject().put("type", "audio/pcm").put("rate", rate);
            JSONObject vad = new JSONObject().put("type", "server_vad");
            if (td.silenceMs != null) {
                vad.put("silence_duration_ms", td.silenceMs.intValue());
            }
            if (td.prefixPaddingMs != null) {
                vad.put("prefix_padding_ms", td.prefixPaddingMs.intValue());
            }
            JSONObject session = new JSONObject()
                    .put("instructions", instructions)
                    .put("voice", voice)
                    .put("turn_detection", vad)
                    .put("audio", new JSONObject()
                            .put("input", new JSONObject().put("format", format))
                            .put("output", new JSONObject().put("format", new JSONObject(format.toString()))));
            if (tools.length() > 0) {
                session.put("tools", tools);
            }
            return new JSONObject().put("type", "session.update").put("session", session).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One chunk of owner audio (16-bit PCM). */
    static String append(byte[] pcm, int off, int len) {
        byte[] chunk = pcm;
        if (off != 0 || len != pcm.length) {
            chunk = java.util.Arrays.copyOfRange(pcm, off, off + len);
        }
        try {
            return new JSONObject().put("type", "input_audio_buffer.append")
                    .put("audio", Base64.getEncoder().encodeToString(chunk)).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One conversation item (a message), for seeding a new session with the conversation so far. */
    static String itemCreate(JSONObject item) {
        try {
            return new JSONObject().put("type", "conversation.item.create").put("item", item).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * VOS's note to the model, as a system message item (decision 48: the words the owner heard of a cut
     * reply). {@code eventId} lets a refusal be told apart from the turn's other errors.
     */
    static String systemNote(String text, String eventId) {
        try {
            JSONObject item = new JSONObject().put("type", "message").put("role", "system")
                    .put("content", new JSONArray().put(new JSONObject().put("type", "input_text").put("text", text)));
            return new JSONObject().put("type", "conversation.item.create").put("event_id", eventId).put("item", item)
                    .toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A tool's result for the model, as a function_call_output item. */
    static String functionOutput(String callId, String outputJson) {
        try {
            JSONObject item = new JSONObject().put("type", "function_call_output").put("call_id", callId)
                    .put("output", outputJson);
            return new JSONObject().put("type", "conversation.item.create").put("item", item).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Asks the model to respond (after a tool result). */
    static String responseCreate() {
        return "{\"type\":\"response.create\"}";
    }

    /** Cancels the active response (accepted without an error by xAI, 2026-10-20). */
    static String responseCancel() {
        return "{\"type\":\"response.cancel\"}";
    }

    /** Parses one server event and calls the handler. Unknown events and bad JSON are ignored. */
    static void dispatch(String text, Handler h) {
        JSONObject m;
        try {
            m = new JSONObject(text);
        } catch (JSONException e) {
            return;
        }
        switch (m.optString("type")) {
            case "session.created":
                JSONObject s = m.optJSONObject("session");
                h.sessionCreated(s == null ? null : s.optString("model", null));
                break;
            case "session.updated":
                h.sessionUpdated();
                break;
            case "input_audio_buffer.speech_started":
                h.speechStarted();
                break;
            case "input_audio_buffer.speech_stopped":
                h.speechStopped();
                break;
            case "conversation.item.input_audio_transcription.completed":
                h.ownerTranscript(m.optString("transcript", ""));
                break;
            case "response.created":
                JSONObject c = m.optJSONObject("response");
                h.responseCreated(c == null ? "" : c.optString("id", ""));
                break;
            case "response.output_audio.delta":
                String delta = m.optString("delta", "");
                if (!delta.isEmpty()) {
                    try {
                        h.audio(m.optString("response_id", ""), Base64.getDecoder().decode(delta));
                    } catch (IllegalArgumentException ignored) {
                        // not base64: dropped, never played as noise
                    }
                }
                break;
            case "response.output_audio_transcript.delta":
                h.replyTranscriptDelta(m.optString("response_id", ""), m.optString("delta", ""));
                break;
            case "response.output_audio_transcript.done":
                h.replyTranscript(m.optString("response_id", ""), m.optString("transcript", ""));
                break;
            case "response.function_call_arguments.done":
                h.functionCall(m.optString("response_id", ""), m.optString("call_id", ""), m.optString("name", ""),
                        m.optString("arguments", "{}"));
                break;
            case "response.done":
                JSONObject r = m.optJSONObject("response");
                h.responseDone(r == null ? "" : r.optString("id", ""), r == null ? "" : r.optString("status", ""));
                break;
            case "error":
                JSONObject e = m.optJSONObject("error");
                h.error(e == null ? "unknown" : e.optString("code", e.optString("type", "unknown")),
                        e == null ? "" : e.optString("message", ""), e == null ? "" : e.optString("event_id", ""));
                break;
            default:
                break;
        }
    }
}
