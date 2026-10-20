package com.vos.session;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

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
        @Override public void replyTranscript(String t) { calls.add("reply " + t); }
        @Override public void responseDone(String id, String s) { calls.add("done " + s); }
        @Override public void error(String c, String m) { calls.add("error " + c + " " + m); }
        @Override public void functionCall(String id, String n, String a) { calls.add("call " + id + " " + n + " " + a); }
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
            "{\"type\":\"response.output_audio.delta\",\"response_id\":\"r-1\",\"delta\":\""
                    + Base64.getEncoder().encodeToString(pcm) + "\"}",
            "{\"type\":\"response.output_audio_transcript.delta\",\"delta\":\"It's\"}",
            "{\"type\":\"response.output_audio_transcript.done\",\"transcript\":\"It's 7:52.\"}",
            "{\"type\":\"response.done\",\"response\":{\"status\":\"completed\"}}",
            "{\"type\":\"response.function_call_arguments.done\",\"call_id\":\"call-9\",\"name\":\"volume_set\","
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
                "owner What time is it?", "audio r-1 4", "reply It's 7:52.", "done completed",
                "call call-9 volume_set {\"percent\":30,\"stream\":\"media\"}", "error invalid_audio bad"),
                r.calls);
        assertArrayEquals(pcm, r.audio);
    }
}
