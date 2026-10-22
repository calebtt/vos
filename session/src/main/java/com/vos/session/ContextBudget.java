package com.vos.session;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Decision 46's token budget for a context the session keeps across turns (§9 Q44). The budget is a
 * share of the bound model's context window, minus the fixed prefix (instructions, tool definitions)
 * and the output reserved for the reply. Pure: tokens are estimated from UTF-8 bytes (about 4 per
 * token for English), which over-counts rather than under-counts for the vendor's tokenizers.
 */
final class ContextBudget {
    /** Q44's default share for chat_voice / chat_text. */
    static final double DEFAULT_SHARE = 0.5;
    /** Q44: the last turns stay verbatim while they fit (6 exchanges, owner plus VOS). */
    static final int VERBATIM_ITEMS = 12;

    final int budget;

    ContextBudget(int budget) {
        this.budget = budget;
    }

    /** {@code share} of {@code window}, minus the prefix and the reserved output; never below zero. */
    static ContextBudget of(int window, double share, int prefixTokens, int reservedOutput) {
        return new ContextBudget(Math.max(0, (int) Math.floor(window * share) - prefixTokens - reservedOutput));
    }

    static int tokens(String text) {
        return text == null ? 0 : (text.getBytes(StandardCharsets.UTF_8).length + 3) / 4;
    }

    /** Per seeded message: the role and item wrapper the vendor counts. */
    static final int PER_ITEM = 4;

    /** What a context costs as seeded: the summary and every item, with the text they are wrapped in. */
    static int tokens(String summary, List<Conversation.Item> items) {
        int t = summary == null ? 0 : tokens(Conversation.summaryFrame(true)) + tokens(summary) + 4 + PER_ITEM;
        for (Conversation.Item i : items) {
            t += tokens(i.text) + PER_ITEM + (i.role == Conversation.Role.TOOL ? tokens(Conversation.TOOL_FRAME) + 1 : 0);
        }
        return t;
    }

    boolean fits(String summary, List<Conversation.Item> items) {
        return tokens(summary, items) <= budget;
    }

    /**
     * How many of the oldest items to compact so the rest, with a summary of at most {@code summaryCap}
     * tokens, fits: at least everything before the verbatim tail, and more of the tail (oldest first)
     * only while it still does not fit. Returns 0 when the context already fits.
     */
    int toCompact(String summary, List<Conversation.Item> items, int summaryCap) {
        if (fits(summary, items)) {
            return 0;
        }
        int n = Math.max(0, items.size() - VERBATIM_ITEMS);
        int frame = tokens(Conversation.summaryFrame(true)) + 4 + PER_ITEM;
        while (n < items.size() && summaryCap + frame + tokens(null, items.subList(n, items.size())) > budget) {
            n++;
        }
        return Math.max(n, 1);
    }
}
