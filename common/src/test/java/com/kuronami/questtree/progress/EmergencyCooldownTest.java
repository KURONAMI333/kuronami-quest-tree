package com.kuronami.questtree.progress;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmergencyCooldownTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID TEAM = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    @Test
    void theWaitBelongsToOnePlayerAndSurvivesTeamChanges() {
        TeamProgress team = new TeamProgress(TEAM);
        team.view(A).setEmergencyReadyAt(5000L);
        team.view(A).setEmergencyWaitNotice(true);

        assertEquals(0L, team.view(B).emergencyReadyAt());
        assertFalse(team.view(B).emergencyWaitNotice());
        assertEquals(5000L, TeamProgress.parse(team.toJson().toString()).view(A).emergencyReadyAt());
        assertTrue(TeamProgress.parse(team.toJson().toString()).view(A).emergencyWaitNotice());

        TeamProgress solo = team.leaveCopy(A);
        assertEquals(5000L, solo.view(A).emergencyReadyAt());
        TeamProgress joiner = new TeamProgress(A);
        joiner.view(A).setEmergencyReadyAt(9000L);
        team.mergeJoiner(A, joiner);
        assertEquals(9000L, team.view(A).emergencyReadyAt());
    }

    @Test
    void anOldSharedWaitMovesToThePlayersWhoHadIt() {
        JsonObject json = JsonParser.parseString("""
                {"id":"00000000-0000-0000-0000-0000000000ff",
                 "shared":{"cooldown":{"#emergency":7777}},
                 "players":{"00000000-0000-0000-0000-00000000000a":{}}}
                """).getAsJsonObject();
        TeamProgress team = TeamProgress.fromJson(json);

        assertEquals(7777L, team.view(A).emergencyReadyAt());
        assertFalse(team.shared.cooldown.containsKey(PlayerProgress.EMERGENCY_COOLDOWN));
    }
}
