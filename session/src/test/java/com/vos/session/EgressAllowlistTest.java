package com.vos.session;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.Set;
import org.junit.Test;

public class EgressAllowlistTest {
    @Test
    public void emptyByDefaultAllowsNothing() {
        EgressAllowlist list = new EgressAllowlist(Collections.emptySet());
        assertFalse(list.isAllowed("api.example.com"));
        assertFalse(list.isAllowed(null));
    }

    @Test
    public void exactHostMatchOnly() {
        EgressAllowlist list = new EgressAllowlist(Set.of("API.Example.com"));
        assertTrue(list.isAllowed("api.example.com"));
        assertTrue(list.isAllowed("api.example.com."));
        assertFalse(list.isAllowed("evil.api.example.com"));
        assertFalse(list.isAllowed("example.com"));
    }

    @Test
    public void wildcardsAreIgnored() {
        EgressAllowlist list = new EgressAllowlist(Set.of("*.example.com"));
        assertFalse(list.isAllowed("api.example.com"));
        assertFalse(list.isAllowed("*.example.com"));
    }
}
