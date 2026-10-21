package com.kuronami.questtree.team;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The party file used to be one {@code writeUTF} string (2026-10-22 release review). It is now a compound, and the old
 * string form still loads.
 */
class PartyStoreFormatTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID PARTY = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    @Test
    void partiesSaveAsACompoundAndLoadBack() {
        PartyStore store = new PartyStore();
        PartyData data = store.data();
        data.create(OWNER, PARTY, 1_000L);
        data.invite(OWNER, MEMBER, 1_000L);

        CompoundTag tag = store.save(new CompoundTag(), null);
        assertInstanceOf(CompoundTag.class, tag.get("parties"), "parties are a compound, not a capped string");

        PartyStore loaded = PartyStore.load(tag, null);
        assertEquals(PARTY, loaded.data().teamIdOf(OWNER));
        assertTrue(loaded.data().party(PARTY).members.contains(OWNER));
        assertEquals(PARTY, loaded.data().invite(MEMBER).party);
    }

    @Test
    void aStringPartyRecordStillLoads() {
        PartyData data = new PartyData();
        data.create(OWNER, PARTY, 1_000L);

        CompoundTag tag = new CompoundTag();
        tag.putString("parties", data.toJson().toString());

        PartyStore loaded = PartyStore.load(tag, null);
        assertEquals(PARTY, loaded.data().teamIdOf(OWNER));
    }
}
