package com.vos.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;

public class ModelPolicyTest {
    private static ModelPolicy shipped() throws Exception {
        return new ModelPolicy(new String(Files.readAllBytes(Path.of("src/main/res/raw/model_policy.json")),
                StandardCharsets.UTF_8));
    }

    @Test
    public void theShippedPolicyBindsChatVoiceToTheXaiRealtimeModel() throws Exception {
        ModelPolicy p = shipped();
        ModelPolicy.Entry e = p.pick("chat_voice", null, false, x -> true);
        assertEquals("xai:grok-voice-3.0", e.id);
        assertEquals("realtime_voice", e.kind);
        assertEquals("wss://api.x.ai/v1/realtime", e.get("url"));
        assertEquals("grok-voice-3.0", e.get("model"));
        assertEquals("api.x.ai", e.fields.getJSONArray("egress_hosts").getString(0));
    }

    @Test
    public void anUnavailableBackendIsSkippedAndNothingLeftIsNull() throws Exception {
        assertNull(shipped().pick("chat_voice", null, false, x -> false));
        assertNull(shipped().pick("ui_step", null, true, x -> true));
    }

    @Test
    public void theDebugOverrideWorksOnlyInDebugBuilds() throws Exception {
        ModelPolicy p = shipped();
        assertEquals("debug:echo", p.pick("chat_voice", "debug:echo", true, x -> true).id);
        assertEquals("xai:grok-voice-3.0", p.pick("chat_voice", "debug:echo", false, x -> true).id);
        assertEquals("xai:grok-voice-3.0", p.pick("chat_voice", "no-such-model", true, x -> true).id);
    }

    @Test
    public void aPolicyNamingAnUnknownModelOrRepeatingAnIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ModelPolicy(
                "{\"registry\":[],\"policy\":{\"chat_voice\":[\"ghost\"]}}"));
        assertThrows(IllegalArgumentException.class, () -> new ModelPolicy(
                "{\"registry\":[{\"id\":\"a\",\"kind\":\"k\"},{\"id\":\"a\",\"kind\":\"k\"}],\"policy\":{}}"));
    }

    /** A registry with one cloud model, one session-local and one actuator model, and {@code policy}. */
    private static String withPolicy(String policy) {
        return "{\"registry\":["
                + "{\"id\":\"cloud\",\"kind\":\"realtime_voice\",\"where\":\"cloud:xai\"},"
                + "{\"id\":\"local\",\"kind\":\"llm_text\",\"where\":\"on_device_session\"},"
                + "{\"id\":\"grammar-a\",\"kind\":\"grammar\",\"where\":\"on_device_actuator\"},"
                + "{\"id\":\"bare\",\"kind\":\"grammar\"}"
                + "],\"policy\":" + policy + "}";
    }

    @Test
    public void anActuatorClassBoundToACloudOrSessionModelIsRejected() {
        // M1-MODEL-02: offline_grammar and ocr_commit (and every actuator class) bind only on-device actuator models.
        for (String taskClass : ModelPolicy.ACTUATOR_CLASSES) {
            for (String id : new String[] {"cloud", "local", "bare"}) {
                IllegalArgumentException e = assertThrows(taskClass + " -> " + id, IllegalArgumentException.class,
                        () -> new ModelPolicy(withPolicy("{\"" + taskClass + "\":[\"grammar-a\",\"" + id + "\"]}")));
                assertTrue(e.getMessage(), e.getMessage().contains(taskClass) && e.getMessage().contains(id));
            }
            assertEquals("grammar-a", new ModelPolicy(withPolicy("{\"" + taskClass + "\":[\"grammar-a\"]}"))
                    .pick(taskClass, null, true, x -> true).id);
        }
        assertEquals("cloud", new ModelPolicy(withPolicy("{\"chat_voice\":[\"cloud\"]}"))
                .pick("chat_voice", null, false, x -> true).id);
    }

    @Test
    public void anOverrideCannotPointAnActuatorClassAtACloudModel() {
        ModelPolicy p = new ModelPolicy(withPolicy("{\"offline_grammar\":[\"grammar-a\"],\"chat_voice\":[\"cloud\"]}"));
        assertEquals("grammar-a", p.pick("offline_grammar", "cloud", true, x -> true).id);
        assertEquals("grammar-a", p.pick("offline_grammar", "local", true, x -> true).id);
        assertNull("nothing on-device left: no answer, never the cloud", p.pick("ocr_commit", "cloud", true, x -> true));
        assertEquals("the override still works for a session class", "local",
                p.pick("chat_voice", "local", true, x -> true).id);
    }

    @Test
    public void theShippedPolicyKeepsEveryActuatorClassOnDevice() throws Exception {
        ModelPolicy p = shipped(); // loading it is the check; this also covers a class it binds later
        for (String taskClass : ModelPolicy.ACTUATOR_CLASSES) {
            for (String id : p.candidates(taskClass)) {
                assertEquals(taskClass + " -> " + id, "on_device_actuator", p.entry(id).where);
            }
        }
    }

    @Test
    public void theActuatorClassesMatchTheStaticCheckList() throws Exception {
        Set<String> listed = Files.readAllLines(Path.of("../ci/actuator-model-classes.txt"), StandardCharsets.UTF_8)
                .stream().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .collect(Collectors.toSet());
        assertEquals(listed, ModelPolicy.ACTUATOR_CLASSES);
    }
}
