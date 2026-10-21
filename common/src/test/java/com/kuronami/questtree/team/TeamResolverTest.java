package com.kuronami.questtree.team;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which provider supplies teams: FTB Teams only when it is installed, otherwise the built-in party. */
class TeamResolverTest {

    @Test
    void builtInPartyIsUsedWithoutFtbTeams() {
        assertEquals(TeamResolver.Source.BUILTIN, TeamResolver.source(false));
        assertEquals(TeamResolver.Source.FTB_TEAMS, TeamResolver.source(true));
    }

    @Test
    void onlyTheOwnerOrASoloPlayerActsForTheTeam() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertTrue(TeamResolver.owns(a, null), "no team record: solo");
        assertTrue(TeamResolver.owns(a, a));
        assertFalse(TeamResolver.owns(b, a));
    }

    /** An unreadable FTB Teams team is refused; only a player FTB says has no team is solo and owns the actions. */
    @Test
    void anUnreadableTeamOwnerIsRefusedButASoloPlayerStillOwns() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertTrue(TeamResolver.ownsTeam(a, null, false), "no team: solo");
        assertFalse(TeamResolver.ownsTeam(a, null, true), "team with an unreadable owner");
        assertTrue(TeamResolver.ownsTeam(a, a, true));
        assertFalse(TeamResolver.ownsTeam(a, b, true));
    }

    @Test
    void aPlayerOutsideAnyPartyIsTheirOwnTeam() {
        UUID player = UUID.randomUUID();
        assertEquals(player, new PartyData().teamIdOf(player));
        assertEquals(java.util.Set.of(player), new PartyData().members(player));
    }
}
