package com.kuronami.questtree.client.edit;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoveBatchTest {

    @Test
    void applyShowsTheTargetsAndOneRefusalPutsTheOriginalsBack() {
        Map<String, double[]> before = new LinkedHashMap<>();
        before.put("a", new double[]{1, 1});
        before.put("b", new double[]{4, 2});
        Map<String, double[]> after = new LinkedHashMap<>();
        after.put("a", new double[]{1.5, 1.5});
        after.put("b", new double[]{4.5, 2.5});
        MoveBatch batch = new MoveBatch(before, after);

        Map<String, double[]> overlay = new LinkedHashMap<>();
        batch.apply(overlay);
        assertEquals(1.5, overlay.get("a")[0]);

        assertTrue(batch.refuse(), "the first refusal rolls back");
        assertFalse(batch.refuse(), "a second reply from the same batch does not roll back again");
        batch.rollback(overlay);
        assertEquals(1.0, overlay.get("a")[0]);
        assertEquals(2.0, overlay.get("b")[1]);
    }
}
