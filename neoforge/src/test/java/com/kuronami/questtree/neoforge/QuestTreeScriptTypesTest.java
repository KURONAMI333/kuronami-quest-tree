package com.kuronami.questtree.neoforge;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A reload of one script type leaves the other types' registrations alone. */
class QuestTreeScriptTypesTest {

    @Test
    void serverReloadDoesNotForgetStartupTypes() {
        QuestTreeScriptTypes types = new QuestTreeScriptTypes();
        types.task("STARTUP", "example:startup");
        types.task("SERVER", "example:server");
        types.condition("STARTUP", "example:condition");

        QuestTreeScriptTypes.Cleared server = types.clear("SERVER");
        assertEquals(Set.of("example:server"), server.tasks());
        assertTrue(server.rewards().isEmpty());
        assertTrue(server.conditions().isEmpty());

        assertTrue(types.clear("SERVER").tasks().isEmpty(), "a second server reload has nothing left to forget");
        QuestTreeScriptTypes.Cleared startup = types.clear("STARTUP");
        assertEquals(Set.of("example:startup"), startup.tasks());
        assertEquals(Set.of("example:condition"), startup.conditions());
    }
}
