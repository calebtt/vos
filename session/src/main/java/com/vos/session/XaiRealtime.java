package com.vos.session;

import java.util.Base64;
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

        void speechStopped();

        void ownerTranscript(String text);

        /** Reply audio; {@code responseId} names the response it belongs to (barge-in drops by it). */
        void audio(String responseId, byte[] pcm);

        void replyTranscript(String text);

        /** A response finished; {@code status} is e.g. "completed" or "cancelled" (the owner talked over it). */
        void responseDone(String responseId, String status);

        void error(String code, String message);
    }

    private XaiRealtime() {
    }

    /** Configures the session: instructions, voice, server VAD, PCM at {@code rate} both ways. */
    static String sessionUpdate(String instructions, String voice, int rate) {
        try {
            JSONObject format = new JSONObject().put("type", "audio/pcm").put("rate", rate);
            JSONObject session = new JSONObject()
                    .put("instructions", instructions)
                    .put("voice", voice)
                    .put("turn_detection", new JSONObject().put("type", "server_vad"))
                    .put("audio", new JSONObject()
                            .put("input", new JSONObject().put("format", format))
                            .put("output", new JSONObject().put("format", new JSONObject(format.toString()))));
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
            case "response.output_audio_transcript.done":
                h.replyTranscript(m.optString("transcript", ""));
                break;
            case "response.done":
                JSONObject r = m.optJSONObject("response");
                h.responseDone(r == null ? "" : r.optString("id", ""), r == null ? "" : r.optString("status", ""));
                break;
            case "error":
                JSONObject e = m.optJSONObject("error");
                h.error(e == null ? "unknown" : e.optString("code", e.optString("type", "unknown")),
                        e == null ? "" : e.optString("message", ""));
                break;
            default:
                break;
        }
    }
}
