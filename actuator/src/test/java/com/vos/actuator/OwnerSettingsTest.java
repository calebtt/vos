package com.vos.actuator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class OwnerSettingsTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private final List<Map<String, Object>> audit = new ArrayList<>();

    private File file() {
        return new File(tmp.getRoot(), "settings/owner.json");
    }

    private OwnerSettings open() {
        return new OwnerSettings(file(), audit::add);
    }

    @Test
    public void theDefaultsMatchTheDecisions() {
        OwnerSettings s = open();
        assertTrue("decision 17", s.on("wake_after_unlock"));
        assertTrue("decision 43", s.on("wake_locked"));
        assertTrue("decision 18", s.on("wake_aec"));
        assertFalse("decision 17: optional", s.on("long_press_fallback"));
        assertEquals("decision 20", "full", s.get("agent_mode"));
        assertFalse("decision 20", s.on("quiet_headset_speech"));
        assertEquals("Q33", "standard", s.get("patience"));
        assertEquals(OwnerSettings.KEYS.keySet(), s.all().keySet());
        assertTrue("reading writes no audit line", audit.isEmpty());
        assertFalse("nothing is written until a change", file().exists());
    }

    @Test
    public void aChangeIsAuditedOnceReadBackAndKeptAcrossARestart() {
        OwnerSettings s = open();
        assertNull(s.set("agent_mode", "quiet", "qs_tile"));
        assertEquals("quiet", s.get("agent_mode"));
        assertEquals(1, audit.size());
        Map<String, Object> line = audit.get(0);
        assertEquals("setting.changed", line.get("event"));
        assertEquals("agent_mode", line.get("key"));
        assertEquals("full", line.get("from"));
        assertEquals("quiet", line.get("to"));
        assertEquals("qs_tile", line.get("source"));

        assertNull("the same value again is not a change", s.set("agent_mode", "quiet", "voice"));
        assertEquals(1, audit.size());

        OwnerSettings again = open();
        assertEquals("quiet", again.get("agent_mode"));
        assertEquals("the others keep their defaults", "standard", again.get("patience"));
    }

    @Test
    public void anInvariantOrAnUnknownKeyIsRefusedAndAudited() {
        OwnerSettings s = open();
        for (String invariant : OwnerSettings.INVARIANTS) {
            String why = s.set(invariant, OwnerSettings.OFF, "voice");
            assertEquals(invariant, "a security invariant has no off switch", why);
        }
        assertEquals("no such setting", s.set("wake_word_volume", "on", "chat"));
        assertEquals("no such setting", s.set(null, "on", "chat"));
        assertEquals(OwnerSettings.INVARIANTS.size() + 2, audit.size());
        for (Map<String, Object> line : audit) {
            assertEquals("setting.refused", line.get("event"));
        }
        assertTrue("no invariant is ever a key",
                java.util.Collections.disjoint(OwnerSettings.KEYS.keySet(), OwnerSettings.INVARIANTS));
        assertFalse(file().exists());
    }

    @Test
    public void aValueOutsideTheKeysListIsRefused() {
        OwnerSettings s = open();
        assertEquals("allowed: full, quiet, paused", s.set("agent_mode", "asleep", "chat"));
        assertNotNull(s.set("wake_aec", "true", "settings"));
        assertNotNull(s.set("patience", null, "settings"));
        assertEquals("full", s.get("agent_mode"));
        assertTrue(s.on("wake_aec"));
        assertEquals(3, audit.size());
        assertEquals("asleep", audit.get(0).get("value"));
    }

    @Test
    public void aLongKeyOrSourceIsClippedInTheAudit() {
        String longKey = "k".repeat(500);
        open().set(longKey, "on", "s".repeat(500));
        assertEquals(64, ((String) audit.get(0).get("key")).length());
        assertEquals(64, ((String) audit.get(0).get("source")).length());
    }

    @Test
    public void aBadFileMeansTheDefaultsAndOneAuditLine() throws Exception {
        file().getParentFile().mkdirs();
        Files.write(file().toPath(), "{not json".getBytes(StandardCharsets.UTF_8));
        OwnerSettings s = open();
        assertEquals("full", s.get("agent_mode"));
        assertEquals(1, audit.size());
        assertEquals("settings.unreadable", audit.get(0).get("event"));
        assertNull("the next change rewrites it", s.set("patience", "long", "settings"));
        assertEquals("long", open().get("patience"));
    }

    @Test
    public void aStoredValueThatIsNotAllowedFallsBackToTheDefault() throws Exception {
        file().getParentFile().mkdirs();
        Files.write(file().toPath(), ("{\"agent_mode\":\"asleep\",\"wake_aec\":\"off\",\"uid_split\":\"off\","
                + "\"patience\":true}").getBytes(StandardCharsets.UTF_8));
        OwnerSettings s = open();
        assertEquals("full", s.get("agent_mode"));
        assertFalse("a valid stored value is kept", s.on("wake_aec"));
        assertEquals("standard", s.get("patience"));
        assertFalse("an invariant in the file is not a setting", s.all().containsKey("uid_split"));
    }

    @Test
    public void readingAnUnknownKeyIsABug() {
        assertThrows(IllegalArgumentException.class, () -> open().get("agent_mood"));
    }
}
