package com.vos.session;

import java.util.Collections;
import java.util.List;
import org.json.JSONObject;

/**
 * A voice turn's view of the shared conversation (decisions 21 and 46): checked against its budget
 * before the turn, seeded into the new realtime session, appended to during the turn, and compacted
 * after it.
 */
interface ContextKeeper {
    /** Null if the turn may start; otherwise why not (the conversation cannot get under its budget). */
    String beforeTurn(String turnId);

    /** The realtime API items that carry the conversation into the new session, oldest first. */
    List<JSONObject> seed();

    void owner(String turnId, String text);

    void vos(String turnId, String text);

    void tool(String turnId, String name, String result);

    /** VOS's note after a barge-in: the words the owner heard of the cut reply (decision 48). */
    void note(String turnId, String text);

    /** The turn is over: compact in the background if the conversation is over its budget. */
    void afterTurn(String turnId);

    /** No shared conversation: each turn starts fresh (tests, the echo backend). */
    ContextKeeper NONE = new ContextKeeper() {
        @Override
        public String beforeTurn(String turnId) {
            return null;
        }

        @Override
        public List<JSONObject> seed() {
            return Collections.emptyList();
        }

        @Override
        public void owner(String turnId, String text) {
        }

        @Override
        public void vos(String turnId, String text) {
        }

        @Override
        public void tool(String turnId, String name, String result) {
        }

        @Override
        public void note(String turnId, String text) {
        }

        @Override
        public void afterTurn(String turnId) {
        }
    };
}
