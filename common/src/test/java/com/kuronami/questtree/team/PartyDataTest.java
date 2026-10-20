package com.kuronami.questtree.team;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The built-in party used when FTB Teams is absent: invites expire, ownership passes on, members are unique. */
class PartyDataTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID PARTY = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    @Test
    void inviteMustBeAcceptedAndExpiresAfterFiveMinutes() {
        PartyData data = new PartyData();
        assertNotNull(data.create(A, PARTY, 0));
        assertTrue(data.invite(A, B, 0));
        assertEquals(PARTY, data.accept(B, 0));
        assertEquals(2, data.members(PARTY).size());

        assertEquals(PARTY, data.leave(B, 0));
        assertTrue(data.invite(A, B, 0));
        assertNull(data.accept(B, PartyData.INVITE_EXPIRY_MS + 1));
        assertNull(data.partyOf(B));
    }

    @Test
    void onePartyPerPlayerAndOnePartyPerId() {
        PartyData data = new PartyData();
        assertNotNull(data.create(A, PARTY, 0));
        assertNull(data.create(B, PARTY, 0));
        assertNull(data.create(A, UUID.randomUUID(), 0));
        assertFalse(data.invite(A, A, 0));
    }

    @Test
    void ownerCanKickAndDissolve() {
        PartyData data = new PartyData();
        data.create(A, PARTY, 0);
        data.invite(A, B, 0);
        data.accept(B, 0);

        assertFalse(data.kick(B, A));
        assertTrue(data.kick(A, B));
        assertNull(data.partyOf(B));

        data.invite(A, C, 0);
        data.accept(C, 0);
        assertEquals(PARTY, data.disband(A));
        assertNull(data.partyOf(A));
        assertNull(data.partyOf(C));
    }

    @Test
    void leavingPassesOwnershipToTheOldestMember() {
        PartyData data = new PartyData();
        data.create(A, PARTY, 0);
        data.invite(A, B, 0);
        data.accept(B, 0);
        data.invite(A, C, 0);
        data.accept(C, 0);

        data.leave(A, 0);
        assertEquals(B, data.partyOf(B).owner);
        assertNotNull(data.partyOf(C));
    }

    @Test
    void savedPartyComesBack() {
        PartyData data = new PartyData();
        data.create(A, PARTY, 0);
        data.invite(A, B, 0);
        data.accept(B, 0);

        PartyData read = PartyData.parse(data.toJson().toString());
        assertEquals(2, read.members(PARTY).size());
        assertEquals(PARTY, read.teamIdOf(B));
    }
}
