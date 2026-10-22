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
 * The {@code compact} task class on xAI's chat completions (§9 Q44: the cheapest model of the vendor
 * that already holds the conversation, so compaction adds no destination, §5.8). The key comes from
 * the {@link KeyVault} per call and only goes into the Authorization header; the request goes through
 * {@link Egress}, so it is allowlisted and logged by host and size, never by content.
 */
final class XaiCompactor implements Conversation.Compactor {
    private static final MediaType JSON = MediaType.get("application/json");
    /** The whole call, so a slow summary cannot hold the next turn past SharedConversation.WAIT_MS. */
    static final long CALL_TIMEOUT_S = 15;
    private final Egress egress;
    private final KeyVault vault;
    private final ModelPolicy.Entry model;

    XaiCompactor(Egress egress, KeyVault vault, ModelPolicy.Entry model) {
        this.egress = egress;
        this.vault = vault;
        this.model = model;
    }

    @Override
    public String model() {
        return model.id;
    }

    @Override
    public String summarize(String previous, List<Conversation.Item> slice, int maxTokens) throws IOException {
        String key;
        try {
            key = vault.get(KeyVault.XAI);
        } catch (GeneralSecurityException e) {
            throw new IOException("key unavailable", e);
        }
        if (key == null) {
            throw new IOException("no xai key");
        }
        String body;
        try {
            body = new JSONObject().put("model", model.get("model")).put("temperature", 0).put("max_tokens", maxTokens)
                    .put("messages", new JSONArray()
                            .put(new JSONObject().put("role", "system").put("content", Conversation.SUMMARIZER_INSTRUCTIONS))
                            .put(new JSONObject().put("role", "user")
                                    .put("content", Conversation.summarizerInput(previous, slice))))
                    .toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        Request req = new Request.Builder().url(model.get("url"))
                .header("Authorization", "Bearer " + key)
                .post(RequestBody.create(body, JSON))
                .build();
        okhttp3.Call call = egress.client().newCall(req);
        call.timeout().timeout(CALL_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS); // inside the next turn's wait
        try (Response r = call.execute()) {
            ResponseBody rb = r.body();
            if (!r.isSuccessful() || rb == null) {
                throw new IOException("compact HTTP " + r.code());
            }
            return new JSONObject(rb.string()).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content");
        } catch (JSONException e) {
            throw new IOException("bad compact reply", e);
        }
    }
}
