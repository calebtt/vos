package com.vos.session;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class XaiRealtimeTest {
    /** Records handler calls as strings. */
    private static final class Rec implements XaiRealtime.Handler {
        final List<String> calls = new ArrayList<>();
        byte[] audio;

        @Override public void sessionCreated(String m) { calls.add("created " + m); }
        @Override public void sessionUpdated() { calls.add("updated"); }
        @Override public void speechStarted() { calls.add("speech started"); }
        @Override public void speechStopped() { calls.add("speech stopped"); }
        @Override public void ownerTranscript(String t) { calls.add("owner " + t); }
        @Override public void audio(String rid, byte[] pcm) { calls.add("audio " + rid + " " + pcm.length); audio = pcm; }
        @Override public void replyTranscriptDelta(String rid, String d) { calls.add("reply-delta " + rid + " " + d); }
        @Override public void replyTranscript(String rid, String t) { calls.add("reply " + rid + " " + t); }
        @Override public void responseDone(String id, String s) { calls.add("done " + s); }
        @Override public void responseCreated(String id) { calls.add("created-response " + id); }
        @Override public void itemAdded(String role) { calls.add("added " + role); }
        @Override public void error(String c, String m, String e) { calls.add("error " + c + " " + m + " " + e); }
        @Override public void functionCall(String r, String id, String n, String a) { calls.add("call " + r + " " + id + " " + n + " " + a); }
    }

    @Test
    public void sessionUpdateAsksFor16kPcmBothWaysAndServerVad() throws Exception {
        JSONObject m = new JSONObject(XaiRealtime.sessionUpdate("be brief", "ara", 16000));
        assertEquals("session.update", m.getString("type"));
        JSONObject s = m.getJSONObject("session");
        assertEquals("be brief", s.getString("instructions"));
        assertEquals("ara", s.getString("voice"));
        assertEquals("server_vad", s.getJSONObject("turn_detection").getString("type"));
        for (String dir : new String[] {"input", "output"}) {
            JSONObject f = s.getJSONObject("audio").getJSONObject(dir).getJSONObject("format");
            assertEquals("audio/pcm", f.getString("type"));
            assertEquals(16000, f.getInt("rate"));
        }
    }

    @Test
    public void endOfTurnSettingsAreSentOnlyWhenSet() throws Exception {
        org.json.JSONArray none = new org.json.JSONArray();
        JSONObject vendor = new JSONObject(XaiRealtime.sessionUpdate("x", "ara", 16000, none,
                TurnDetection.VENDOR_DEFAULT)).getJSONObject("session").getJSONObject("turn_detection");
        assertEquals("only the type: the vendor's own end-of-turn", 1, vendor.length());
        JSONObject set = new JSONObject(XaiRealtime.sessionUpdate("x", "ara", 16000, none,
                new TurnDetection("long", 1500, null))).getJSONObject("session").getJSONObject("turn_detection");
        assertEquals("server_vad", set.getString("type"));
        assertEquals(1500, set.getInt("silence_duration_ms"));
        assertEquals(false, set.has("prefix_padding_ms"));
    }

    @Test
    public void toolsAreSentOnlyWhenThereAreSome() throws Exception {
        org.json.JSONArray tools = new org.json.JSONArray(
                "[{\"type\":\"function\",\"name\":\"time_get\",\"parameters\":{\"type\":\"object\"}}]");
        JSONObject with = new JSONObject(XaiRealtime.sessionUpdate("x", "ara", 16000, tools)).getJSONObject("session");
        assertEquals("time_get", with.getJSONArray("tools").getJSONObject(0).getString("name"));
        JSONObject without = new JSONObject(XaiRealtime.sessionUpdate("x", "ara", 16000)).getJSONObject("session");
        assertEquals(false, without.has("tools"));
    }

    @Test
    public void aToolResultIsAFunctionCallOutputItem() throws Exception {
        JSONObject m = new JSONObject(XaiRealtime.functionOutput("call-1", "{\"status\":\"VERIFIED\"}"));
        assertEquals("conversation.item.create", m.getString("type"));
        JSONObject item = m.getJSONObject("item");
        assertEquals("function_call_output", item.getString("type"));
        assertEquals("call-1", item.getString("call_id"));
        assertEquals("{\"status\":\"VERIFIED\"}", item.getString("output"));
        assertEquals("response.create", new JSONObject(XaiRealtime.responseCreate()).getString("type"));
    }

    @Test
    public void appendCarriesBase64Pcm() throws Exception {
        byte[] pcm = {1, 2, 3, 4, 5, 6};
        JSONObject m = new JSONObject(XaiRealtime.append(pcm, 2, 3));
        assertEquals("input_audio_buffer.append", m.getString("type"));
        assertArrayEquals(new byte[] {3, 4, 5}, Base64.getDecoder().decode(m.getString("audio")));
    }

    @Test
    public void dispatchesTheEventsSeenFromTheLiveApi() {
        // Shapes as the live endpoint sent them on 2026-10-20 (ids shortened).
        byte[] pcm = {10, 0, -10, 0};
        String[] events = {
            "{\"type\":\"session.created\",\"session\":{\"model\":\"grok-voice-3.0\",\"voice\":\"xai_ara\"}}",
            "{\"type\":\"ping\",\"timestamp\":1}",
            "{\"type\":\"session.updated\",\"session\":{\"model\":\"grok-voice-3.0\"}}",
            "{\"type\":\"input_audio_buffer.speech_started\"}",
            "{\"type\":\"input_audio_buffer.speech_stopped\"}",
            "{\"type\":\"conversation.item.input_audio_transcription.completed\",\"transcript\":\"What time is it?\"}",
            "{\"type\":\"response.created\",\"response\":{\"id\":\"r-1\",\"status\":\"in_progress\"}}",
            "{\"type\":\"response.output_audio.delta\",\"response_id\":\"r-1\",\"delta\":\""
                    + Base64.getEncoder().encodeToString(pcm) + "\"}",
            "{\"type\":\"response.output_audio_transcript.delta\",\"delta\":\"It's\"}",
            "{\"type\":\"response.output_audio_transcript.done\",\"transcript\":\"It's 7:52.\"}",
            "{\"type\":\"response.done\",\"response\":{\"status\":\"completed\"}}",
            "{\"type\":\"response.function_call_arguments.done\",\"response_id\":\"r-2\",\"call_id\":\"call-9\",\"name\":\"volume_set\","
                    + "\"arguments\":\"{\\\"percent\\\":30,\\\"stream\\\":\\\"media\\\"}\"}",
            "{\"type\":\"error\",\"error\":{\"code\":\"invalid_audio\",\"message\":\"bad\"}}",
            "not json",
            "{\"type\":\"response.output_audio.delta\",\"delta\":\"%%%\"}",
        };
        Rec r = new Rec();
        for (String e : events) {
            XaiRealtime.dispatch(e, r);
        }
        assertEquals(List.of("created grok-voice-3.0", "updated", "speech started", "speech stopped",
                "owner What time is it?", "created-response r-1", "audio r-1 4", "reply-delta  It's",
                "reply  It's 7:52.", "done completed",
                "call r-2 call-9 volume_set {\"percent\":30,\"stream\":\"media\"}", "error invalid_audio bad "),
                r.calls);
        assertArrayEquals(pcm, r.audio);
    }

    @Test
    public void transcriptsCarryTheirResponseIdAndAnErrorNamesTheEventItRefused() {
        Rec r = new Rec();
        XaiRealtime.dispatch("{\"type\":\"response.output_audio_transcript.delta\",\"response_id\":\"r-3\",\"delta\":\"Sure\"}", r);
        XaiRealtime.dispatch("{\"type\":\"response.output_audio_transcript.done\",\"response_id\":\"r-3\","
                + "\"transcript\":\"Sure, here it is.\"}", r);
        XaiRealtime.dispatch("{\"type\":\"error\",\"error\":{\"code\":\"invalid_value\",\"message\":\"no\","
                + "\"event_id\":\"vos-heard-1\"}}", r);
        assertEquals(List.of("reply-delta r-3 Sure", "reply r-3 Sure, here it is.", "error invalid_value no vos-heard-1"),
                r.calls);
    }

    @Test
    public void itemAddedAndXaiErrorsAsTheLiveApiSentThem() {
        // Shapes from the PR #309 probe on the live API (2026-11-01; ids shortened, params cut).
        Rec r = new Rec();
        XaiRealtime.dispatch("{\"type\":\"conversation.item.added\",\"event_id\":\"s-1\",\"item\":{\"id\":\"i-1\","
                + "\"object\":\"realtime.item\",\"type\":\"message\",\"status\":\"completed\",\"role\":\"system\","
                + "\"content\":[{\"type\":\"input_text\",\"text\":\"The owner interrupted ...\"}],\"replayed\":false}}", r);
        XaiRealtime.dispatch("{\"type\":\"conversation.item.created\",\"item\":{\"role\":\"user\"}}", r); // OpenAI's name
        XaiRealtime.dispatch("{\"type\":\"conversation.item.added\"}", r);
        // xAI's error.event_id is the error's own server id, never the client's event_id.
        XaiRealtime.dispatch("{\"type\":\"error\",\"event_id\":\"aaea872d\",\"error\":{\"message\":\"Invalid event received\","
                + "\"type\":\"invalid_request_error\",\"code\":\"invalid_event\",\"event_id\":\"aaea872d\","
                + "\"params\":\"1 validation error for RealtimeClientEvent\\nitem.role ...\"},\"previous_item_id\":null}", r);
        assertEquals(List.of("added system", "added user", "added ", "error invalid_event Invalid event received aaea872d"),
                r.calls);
    }

    @Test
    public void aSeededItemCanCarryAnEventId() throws Exception {
        JSONObject item = new JSONObject("{\"type\":\"message\",\"role\":\"system\",\"content\":[{\"type\":\"input_text\",\"text\":\"n\"}]}");
        JSONObject m = new JSONObject(XaiRealtime.itemCreate(item, "vos-heard-seed-1"));
        assertEquals("conversation.item.create", m.getString("type"));
        assertEquals("vos-heard-seed-1", m.getString("event_id"));
        assertEquals("system", m.getJSONObject("item").getString("role"));
        assertTrue(!new JSONObject(XaiRealtime.itemCreate(item)).has("event_id"));
    }

    @Test
    public void theHeardNoteIsASystemMessageItemWithAnEventId() throws Exception {
        JSONObject m = new JSONObject(XaiRealtime.systemNote("The owner interrupted ...", "vos-heard-2"));
        assertEquals("conversation.item.create", m.getString("type"));
        assertEquals("vos-heard-2", m.getString("event_id"));
        JSONObject item = m.getJSONObject("item");
        assertEquals("message", item.getString("type"));
        assertEquals("system", item.getString("role"));
        JSONObject part = item.getJSONArray("content").getJSONObject(0);
        assertEquals("input_text", part.getString("type"));
        assertEquals("The owner interrupted ...", part.getString("text"));
        assertEquals(1, item.getJSONArray("content").length());
    }
}
