package com.kuronami.questtree.team;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The team view the server sends: round trip, solo/team distinction and invite expiry, without the game. */
class TeamViewTest {

    private static final String A = "00000000-0000-0000-0000-00000000000a";
    private static final String B = "00000000-0000-0000-0000-00000000000b";

    @Test
    void jsonKeepsMembersAndInvites() {
        TeamView view = new TeamView("team", "Miners", A, "Alice", false, true,
                List.of(new TeamView.Member(A, "Alice", true, true, true),
                        new TeamView.Member(B, "Bob", false, false, false)),
                List.of(new TeamView.Invite("other", "Builders", "Bob", 5000)));

        TeamView read = TeamView.parse(view.toJson().toString());

        assertTrue(read.inTeam());
        assertTrue(read.isOwner());
        assertEquals("Miners", read.name());
        assertEquals("Bob", read.nameOf(B));
        assertNotNull(read.self());
        assertTrue(read.hasInvites());
        assertEquals("Builders", read.invites().get(0).partyName());
        assertEquals("Bob", read.invites().get(0).fromName());
    }

    @Test
    void soloPlayerIsNotInAPartyAndHasNoInvites() {
        TeamView solo = new TeamView(A, "", A, "Alice", false, false,
                List.of(new TeamView.Member(A, "Alice", true, true, true)), List.of());

        assertFalse(solo.inTeam());
        assertFalse(solo.active());
        assertEquals("Alice", solo.nameOf(A));
    }

    @Test
    void soloPlayersOwnSharedActionsAndMembersDoNot() {
        TeamView solo = new TeamView(A, "", A, "Alice", false, false,
                List.of(new TeamView.Member(A, "Alice", true, true, true)), List.of());
        TeamView member = new TeamView("team", "", A, "Alice", false, true,
                List.of(new TeamView.Member(A, "Alice", true, false, true),
                        new TeamView.Member(B, "Bob", true, true, false)), List.of());

        assertTrue(solo.ownsSharedActions());
        assertFalse(member.isOwner());
        assertFalse(member.ownsSharedActions());
    }

    @Test
    void aPartyWithOnlyItsOwnerIsAlreadyATeam() {
        TeamView party = new TeamView(A, "", A, "Alice", false, true,
                List.of(new TeamView.Member(A, "Alice", true, true, true)), List.of());

        assertTrue(party.inTeam());
        assertTrue(party.active());
        assertTrue(party.isOwner());
    }

    @Test
    void expiredInvitesAreHiddenAndAnInviteAloneActivatesTheRow() {
        TeamView invited = new TeamView(A, "", A, "Alice", false, false,
                List.of(new TeamView.Member(A, "Alice", true, true, true)),
                List.of(new TeamView.Invite("p", "Builders", "Bob", 1000)));

        assertTrue(invited.active());
        assertEquals(1, invited.activeInvites(999).size());
        assertEquals(0, invited.activeInvites(1000).size());
        assertTrue(invited.hasActiveInvites(999));
        assertFalse(invited.hasActiveInvites(1000));
    }

    /** An FTB team whose owner the server could not read keeps the shared actions off, even for its members. */
    @Test
    void anFtbTeamWithoutAReadableOwnerBlocksSharedActions() {
        TeamView unknown = new TeamView("team", "Miners", "", "", true, true,
                List.of(new TeamView.Member(A, "Alice", true, true, false),
                        new TeamView.Member(B, "Bob", true, false, false)), List.of());

        assertFalse(unknown.ownerKnown());
        assertFalse(unknown.isOwner());
        assertFalse(unknown.ownsSharedActions());
        TeamView read = TeamView.parse(unknown.toJson().toString());
        assertFalse(read.ownerKnown());
        assertFalse(read.ownsSharedActions());

        TeamView solo = new TeamView(A, "", A, "Alice", true, false,
                List.of(new TeamView.Member(A, "Alice", true, true, true)), List.of());
        assertTrue(solo.ownerKnown(), "a solo player always owns their pool");
        assertTrue(solo.ownsSharedActions());
    }

    @Test
    void malformedJsonFallsBackToAnEmptyView() {
        TeamView read = TeamView.parse("not json");
        assertFalse(read.inTeam());
        assertFalse(read.hasInvites());
    }
}
