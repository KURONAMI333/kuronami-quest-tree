package com.kuronami.questtree.data;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Without a shape in the node or its chapter, a node is drawn in the shape of its role. */
class RoleShapeTest {

    private static String shape(String json) {
        return ProgressModel.roleShape(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void rolesPickTheirShape() {
        assertEquals("square", shape("{}"));
        assertEquals("pentagon", shape("{\"milestone\": true}"));
        assertEquals("diamond", shape("{\"exclusive\": {\"group\": \"g\"}}"));
        assertEquals("diamond", shape("{\"exclusive_with\": [\"other\"]}"));
        assertEquals("circle", shape("{\"repeat\": {\"cooldown\": 60}}"));
        assertEquals("circle", shape("{\"repeat\": true}"));
        assertEquals("square", shape("{\"repeat\": false}"));
        assertEquals("square", shape("{\"exclusive_with\": []}"));
    }

    @Test
    void milestoneWinsOverOtherRoles() {
        assertEquals("pentagon", shape("{\"milestone\": true, \"repeat\": true, \"exclusive\": {\"group\": \"g\"}}"));
        assertEquals("diamond", shape("{\"repeat\": true, \"exclusive\": {\"group\": \"g\"}}"));
    }
}
