package com.vos.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
