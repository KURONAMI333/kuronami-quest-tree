package com.kuronami.questtree.team;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which provider supplies teams: FTB Teams only when it is installed, otherwise the built-in party. */
class TeamResolverTest {

    @Test
    void builtInPartyIsUsedWithoutFtbTeams() {
        assertEquals(TeamResolver.Source.BUILTIN, TeamResolver.source(false));
        assertEquals(TeamResolver.Source.FTB_TEAMS, TeamResolver.source(true));
    }

    @Test
    void aPlayerOutsideAnyPartyIsTheirOwnTeam() {
        UUID player = UUID.randomUUID();
        assertEquals(player, new PartyData().teamIdOf(player));
        assertEquals(java.util.Set.of(player), new PartyData().members(player));
    }
}
