package com.vos.actuator;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Decision 21: reopening the chat window shows the shared conversation, voice turns included. */
public class ChatThreadTest {
    @Test
    public void aReloadReplacesTheWindowWithTheSharedThread() {
        List<ChatThread.Line> current = List.of(line("owner", "earlier"), line("vos", "old reply"));
        List<ChatThread.Line> fresh = List.of(
                line("owner", "earlier"), line("vos", "old reply"),
                line("owner", "what time is it"), line("vos", "4:10"));
        assertThread(fresh, ChatThread.merge(current, fresh, false));
    }

    @Test
    public void anOpenTypedTurnKeepsItsLineUntilTheSessionHasIt() {
        List<ChatThread.Line> current = List.of(line("owner", "earlier"), line("owner", "what time is it"));
        List<ChatThread.Line> fresh = List.of(line("owner", "earlier"), line("vos", "from voice"));
        List<ChatThread.Line> merged = ChatThread.merge(current, fresh, true);
        assertEquals("voice turns that landed while the window was closed", "from voice", merged.get(1).text);
        assertEquals("owner", merged.get(2).role);
        assertEquals("what time is it", merged.get(2).text);
    }

    @Test
    public void theSameOwnerLineIsNotDuplicatedOnceTheSessionHasIt() {
        ChatThread.Line owner = line("owner", "what time is it");
        List<ChatThread.Line> current = List.of(owner);
        List<ChatThread.Line> fresh = List.of(owner, line("vos", "4:10"));
        assertThread(fresh, ChatThread.merge(current, fresh, true));
    }

    @Test
    public void aTrailingNoteSurvivesTheReload() {
        List<ChatThread.Line> current = List.of(line("owner", "hi"), line("note", "VOS couldn't answer (timed out)."));
        List<ChatThread.Line> fresh = List.of(line("owner", "hi"), line("vos", "voice landed"));
        List<ChatThread.Line> merged = ChatThread.merge(current, fresh, false);
        assertEquals(3, merged.size());
        assertEquals("note", merged.get(2).role);
        assertEquals("voice landed", merged.get(1).text);
    }

    private static ChatThread.Line line(String role, String text) {
        return new ChatThread.Line(role, text);
    }

    private static void assertThread(List<ChatThread.Line> expected, List<ChatThread.Line> actual) {
        assertEquals(text(expected), text(actual));
    }

    private static List<String> text(List<ChatThread.Line> lines) {
        List<String> out = new ArrayList<>();
        for (ChatThread.Line l : lines) {
            out.add(l.role + ":" + l.text);
        }
        return out;
    }
}
