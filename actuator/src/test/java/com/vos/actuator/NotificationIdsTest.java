package com.vos.actuator;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/** No two of the actuator's notifications share an ID, so none replaces or cancels another. */
public class NotificationIdsTest {
    @Test
    public void everyIdIsDistinctAndNonZero() throws IllegalAccessException {
        Map<Integer, String> seen = new HashMap<>();
        for (Field f : NotificationIds.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) {
                continue;
            }
            int id = f.getInt(null);
            assertNotEquals(f.getName() + " is 0, which startForeground rejects", 0, id);
            String other = seen.put(id, f.getName());
            assertTrue(f.getName() + " shares ID " + id + " with " + other, other == null);
        }
        assertTrue("found the IDs", seen.size() >= 4);
    }
}
