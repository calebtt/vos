package com.vos.actuator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** #262: the turn's mic opens before the AudioPlane takes the mode, and never stays open on a refusal. */
public class MicFirstTest {
    private final List<String> order = new ArrayList<>();
    private final Object capture = new Object();

    private final class FakePlane implements MicFirst.Plane {
        boolean admits = true;
        String id = "turn-1"; // what summon() returns; null refuses

        @Override
        public boolean admits() {
            order.add("admits");
            return admits;
        }

        @Override
        public String summon(String source) {
            order.add("summon " + source);
            return id;
        }

        @Override
        public void refuseSummon(String source, String why) {
            order.add("refuse " + source + ": " + why);
        }
    }

    private final class FakeMic implements MicFirst.Mic<Object> {
        boolean fail;
        boolean none; // a debug file mic: nothing to open

        @Override
        public Object open() throws IOException {
            order.add("open");
            if (fail) {
                throw new IOException("mic unavailable");
            }
            return none ? null : capture;
        }

        @Override
        public void close(Object c) {
            order.add("close");
        }
    }

    private final FakePlane plane = new FakePlane();
    private final FakeMic mic = new FakeMic();

    @Test
    public void theMicOpensBeforeThePlaneTakesTheMode() {
        MicFirst.Opened<Object> o = MicFirst.summon(plane, mic, "assist");
        assertEquals(List.of("admits", "open", "summon assist"), order);
        assertEquals("turn-1", o.turnId);
        assertSame(capture, o.capture);
    }

    @Test
    public void aRefusalAfterTheMicOpenedClosesIt() {
        plane.id = null; // a call began between admits() and summon()
        assertNull(MicFirst.summon(plane, mic, "qs_tile"));
        assertEquals(List.of("admits", "open", "summon qs_tile", "close"), order);
    }

    @Test
    public void notAdmittedOpensNoMicAndLetsThePlaneRefuse() {
        plane.admits = false;
        plane.id = null;
        assertNull(MicFirst.summon(plane, mic, "assist"));
        assertEquals(List.of("admits", "summon assist"), order); // summon() writes the refusal line
    }

    @Test
    public void aMicThatDoesNotOpenRefusesWithoutTouchingThePlane() {
        mic.fail = true;
        assertNull(MicFirst.summon(plane, mic, "assist"));
        assertEquals(List.of("admits", "open", "refuse assist: mic unavailable: IOException"), order);
    }

    @Test
    public void admittedOnlyBySummonFallsBackToTheOldOrder() {
        plane.admits = false; // a call ended in between: summon() admits after all
        MicFirst.Opened<Object> o = MicFirst.summon(plane, mic, "assist");
        assertEquals(List.of("admits", "summon assist"), order);
        assertEquals("turn-1", o.turnId);
        assertNull(o.capture); // the turn opens its mic itself
    }

    @Test
    public void aFileMicOpensNothing() {
        mic.none = true;
        MicFirst.Opened<Object> o = MicFirst.summon(plane, mic, "debug_shell");
        assertEquals(List.of("admits", "open", "summon debug_shell"), order);
        assertNull(o.capture);
    }
}
