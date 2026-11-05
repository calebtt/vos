package com.vos.actuator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TaskRecordsTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private final AtomicLong now = new AtomicLong(1_000_000L);

    private File dir() {
        return new File(tmp.getRoot(), "tasks");
    }

    private TaskRecords records() {
        return new TaskRecords(dir(), now::get);
    }

    private static List<JSONObject> lines(String jsonl) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        for (String l : jsonl.split("\n")) {
            out.add(new JSONObject(l));
        }
        return out;
    }

    private static Map<String, Object> step(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    public void aTaskHasOneRecordInOrderEndingWithItsOutcome() throws Exception {
        TaskRecords r = records();
        assertNull(r.open("t1", null, "turn-1", "owner_voice", "set the media volume to 30 percent"));
        assertNull(r.modelPick("t1", "chat_voice", "xai:grok-voice-3.0", null));
        assertNull(r.step("t1", step("executed_action", "volume_set {\"percent\":30}", "oracle", "VERIFIED",
                "latency_ms", 412)));
        assertNull(r.timing("t1", 100L, 900L, 1400L));
        assertNull(r.close("t1", "pass", "VERIFIED", null));
        List<JSONObject> l = lines(r.read("t1"));
        assertEquals(List.of("header", "model_pick", "step", "timing", "outcome"),
                List.of(l.get(0).getString("entry"), l.get(1).getString("entry"), l.get(2).getString("entry"),
                        l.get(3).getString("entry"), l.get(4).getString("entry")));
        assertEquals(TaskRecords.SCHEMA, l.get(0).getString("schema"));
        assertEquals("owner_voice", l.get(0).getString("source"));
        assertEquals("turn-1", l.get(0).getString("turnId"));
        assertTrue(l.get(0).isNull("parent_task_id"));
        assertEquals("t1", l.get(2).getString("task_id"));
        assertEquals(412, l.get(2).getInt("latency_ms"));
        assertEquals("pass", l.get(4).getString("outcome"));

        assertEquals("a task has exactly one record", "the task already has a record",
                r.open("t1", null, "turn-1", "owner_voice", "again"));
        assertEquals("the record is closed", r.step("t1", step("oracle", "VERIFIED")));
        assertEquals("no such record", r.step("t2", step("oracle", "VERIFIED")));
    }

    @Test
    public void sourcesOutcomesAndFieldsAreClosedSets() {
        TaskRecords r = records();
        assertEquals("bad source", r.open("t1", null, null, "owner", "x"));
        assertEquals("bad source", r.open("t1", null, null, "vtb:", "x"));
        assertNull(r.open("t1", null, null, "app_agent:com.example.maps", "x"));
        assertNull(r.open("t2", "t1", null, "vtb:peer-1", "x"));
        assertEquals("bad task id", r.open("../escape", null, null, "agent", "x"));
        assertEquals("bad task id", r.open("t3", "../p", null, "agent", "x"));
        assertEquals("no step field mood", r.step("t1", step("mood", "fine")));
        assertEquals("no compaction field text", r.compaction("t1", step("text", "the summary")));
        assertNull(r.compaction("t1", step("context", "conversation", "tokens_before", 900, "tokens_after", 300,
                "budget", 450, "model", "xai:grok-4.20-0309-non-reasoning")));
        assertEquals("bad outcome", r.close("t1", "done", null, null));
        assertNull(r.close("t1", "fail", "UNVERIFIED", "the show was not found"));
    }

    @Test
    public void aHighRiskRedactionIsACompactionLineWithTheCountOnly() throws Exception {
        TaskRecords r = records();
        r.open("t1", null, null, "owner_voice", "read me the code from Messages");
        assertNull(r.compaction("t1", step("context", "conversation", "tokens_before", 120, "tokens_after", 118,
                "replaced", 2, "saved", true)));
        assertEquals("no compaction field released", r.compaction("t1", step("replaced", 1, "released", "482913")));
        r.close("t1", "pass", "VERIFIED", null);
        JSONObject c = lines(r.read("t1")).get(1);
        assertEquals("compaction", c.getString("entry"));
        assertEquals(2, c.getInt("replaced"));
        assertTrue(c.getBoolean("saved"));
        assertFalse(r.read("t1").contains("482913"));
    }

    @Test
    public void aVetoIsTypedWithAReasonFromQ23() throws Exception {
        TaskRecords r = records();
        r.open("t1", null, null, "owner_chat", "text Alex");
        Map<String, Object> veto = step("reason", "needs_confirmation", "class", "irreversible", "actId", "a1");
        assertNull(r.step("t1", step("proposed_action", "messages.send", "veto", veto)));
        assertEquals("a veto is {reason, class, actId} with a reason from Q23",
                r.step("t1", step("veto", step("reason", "i_said_no", "class", "x", "actId", "a2"))));
        assertEquals("a veto is {reason, class, actId} with a reason from Q23",
                r.step("t1", step("veto", step("reason", "mode_paused"))));
        r.close("t1", "vetoed", null, "needs your confirmation");
        JSONObject s = lines(r.read("t1")).get(1);
        assertEquals("needs_confirmation", s.getJSONObject("veto").getString("reason"));
        assertEquals("a1", s.getJSONObject("veto").getString("actId"));
    }

    @Test
    public void keyLikeStringsAreScrubbedEverywhere() throws Exception {
        TaskRecords r = records();
        String key = "xai-" + "A1b2C3d4E5f6G7h8I9j0";
        r.open("t1", null, null, "owner_chat", "my key is " + key);
        r.step("t1", step("proposed_action", "Authorization: Bearer abcdefgh12345678",
                "observation", step("note", "sk-proj-abcdefghijklmnop", "list", List.of(key))));
        r.close("t1", "fail", key, null);
        String all = r.read("t1");
        assertFalse(all.contains(key));
        assertFalse(all.contains("abcdefgh12345678"));
        assertFalse(all.contains("sk-proj-abcdefghijklmnop"));
        assertTrue(all.contains(TaskRecords.SCRUBBED));
        assertEquals("model id xai:grok-voice-3.0 is not a key", "xai:grok-voice-3.0",
                TaskRecords.scrub("xai:grok-voice-3.0"));
        assertEquals("xai-short", TaskRecords.scrub("xai-short"));
    }

    @Test
    public void viewExportAndDelete() throws Exception {
        TaskRecords r = records();
        for (String id : new String[] {"a", "b", "c"}) {
            r.open(id, null, null, "eval", "task " + id);
            r.close(id, "pass", null, null);
        }
        assertEquals(3, r.taskIds().size());
        assertEquals(6, lines(r.exportAll()).size());
        assertTrue(r.delete("b"));
        assertFalse(r.delete("b"));
        assertNull(r.read("b"));
        assertEquals(2, r.taskIds().size());
        assertEquals(2, r.deleteAll());
        assertTrue(r.taskIds().isEmpty());
        assertEquals("", r.exportAll());
    }

    @Test
    public void aRecordLeftOpenIsClosedAsCancelledOnRestart() throws Exception {
        TaskRecords r = records();
        r.open("t1", null, null, "owner_voice", "what time is it");
        r.open("t2", null, null, "owner_voice", "done one");
        r.close("t2", "pass", null, null);
        TaskRecords again = records();
        List<JSONObject> l = lines(again.read("t1"));
        assertEquals("outcome", l.get(l.size() - 1).getString("entry"));
        assertEquals("cancelled", l.get(l.size() - 1).getString("outcome"));
        assertEquals(2, lines(again.read("t2")).size());
        assertEquals("the record is closed", again.step("t1", step("oracle", "VERIFIED")));
    }

    @Test
    public void closedRecordsOlderThan90DaysArePruned() {
        TaskRecords r = records();
        r.open("old", null, null, "eval", "x");
        r.close("old", "pass", null, null);
        r.open("running", null, null, "eval", "x");
        now.addAndGet(TaskRecords.RETENTION_MS + 1);
        r.open("new", null, null, "eval", "x");
        r.close("new", "pass", null, null);
        assertEquals(1, r.prune());
        assertEquals(java.util.Set.of("running", "new"), new java.util.HashSet<>(r.taskIds()));
        assertNull("an open record is never pruned", r.step("running", step("oracle", "VERIFIED")));
    }

    @Test
    public void oldestFirstFollowsWhenTheRecordWasOpenedNotTheFileMtime() throws Exception {
        TaskRecords r = records();
        assertNull(r.open("a", null, null, "eval", "first"));
        now.addAndGet(10);
        assertNull(r.open("b", null, null, "eval", "second"));
        assertNull(r.close("a", "pass", null, null)); // appending would make a's mtime the newer one
        File fa = new File(dir(), "a.jsonl");
        File fb = new File(dir(), "b.jsonl");
        fb.setLastModified(1_000L);
        fa.setLastModified(fb.lastModified() + 60_000L);
        assertEquals(List.of("a", "b"), r.taskIds());
    }

    @Test
    public void aTornLastLineIsDroppedWhenTheStoreRestarts() throws Exception {
        TaskRecords r = records();
        assertNull(r.open("t1", null, "turn-1", "owner_voice", "what time is it"));
        java.nio.file.Files.write(new File(dir(), "t1.jsonl").toPath(), "{\"entry\":\"step\",\"plan\":\"cut".getBytes(),
                java.nio.file.StandardOpenOption.APPEND);
        TaskRecords again = records();
        String text = again.read("t1");
        assertFalse(text.contains("cut"));
        List<JSONObject> l = lines(text);
        assertEquals("header", l.get(0).getString("entry"));
        assertEquals("turn-1", l.get(0).getString("turnId"));
        assertEquals("outcome", l.get(l.size() - 1).getString("entry"));
        assertEquals("cancelled", l.get(l.size() - 1).getString("outcome"));
    }

    @Test
    public void aPinnedRecordNeverExpires() {
        TaskRecords r = records();
        assertNull(r.open("keep", null, null, "eval", "pinned"));
        assertNull(r.close("keep", "pass", null, null));
        assertNull(r.pin("keep", true));
        assertNull(r.open("drop", null, null, "eval", "old"));
        assertNull(r.close("drop", "pass", null, null));
        now.addAndGet(TaskRecords.RETENTION_MS + 1);
        assertEquals(1, r.prune());
        assertEquals(List.of("keep"), r.taskIds());
        assertEquals("no such record", r.pin("missing", true));
    }
}
