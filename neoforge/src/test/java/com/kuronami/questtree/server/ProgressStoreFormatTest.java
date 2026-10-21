package com.kuronami.questtree.server;

import com.kuronami.questtree.progress.TeamProgress;
import com.kuronami.questtree.progress.TeamStoreData;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The saved progress of one team used to be a single {@code writeUTF} string, capped at 65,535 bytes: a big book
 * failed the save and the next load lost everyone's progress (2026-10-22 release review). These checks keep the new
 * compound form, the old string form, and a realistically large record safe.
 */
class ProgressStoreFormatTest {

    private static final UUID TEAM = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    private static UUID player(int i) {
        return new UUID(0L, i + 1L);
    }

    /** 2,000 completed nodes and 10 members round-trip through the saved NBT without a string record. */
    @Test
    void aLargeBookSavesAsCompoundsAndLoadsBack() {
        TeamStoreData data = new TeamStoreData();
        TeamProgress team = data.team(TEAM);
        for (int i = 0; i < 2000; i++) {
            team.shared.completed.add("test:book/node_" + i);
        }
        for (int i = 0; i < 10; i++) {
            UUID member = player(i);
            data.owners.put(member, TEAM);
            for (int j = 0; j < 200; j++) {
                team.part(member).completed.add("test:own_" + i + "/node_" + j);
            }
            team.part(member).claimedRewards.add("test:book/node_0#0");
        }

        ProgressStore store = new ProgressStore();
        store.replaceData(data);
        CompoundTag tag = store.save(new CompoundTag(), null);

        CompoundTag savedTeams = tag.getCompound("teams");
        assertInstanceOf(CompoundTag.class, savedTeams.get(TEAM.toString()),
                "a team is a compound, not a 65,535-byte-capped string");

        ProgressStore loaded = ProgressStore.load(tag, null);
        TeamProgress back = loaded.team(TEAM);
        assertEquals(2000, back.shared.completed.size());
        for (int i = 0; i < 10; i++) {
            UUID member = player(i);
            assertEquals(TEAM, loaded.teamIdOf(member));
            assertEquals(200, back.players.get(member).completed.size());
            assertTrue(back.players.get(member).claimedRewards.contains("test:book/node_0#0"));
        }
    }

    /** A save written before this fix holds each team in one string and still loads. */
    @Test
    void aStringRecordStillLoads() {
        TeamStoreData data = new TeamStoreData();
        data.team(TEAM).shared.completed.add("test:book/node_0");

        CompoundTag tag = new CompoundTag();
        tag.putInt("version", TeamStoreData.VERSION);
        CompoundTag teams = new CompoundTag();
        teams.putString(TEAM.toString(), data.team(TEAM).toJson().toString());
        tag.put("teams", teams);
        tag.put("owners", new CompoundTag());
        tag.put("migrations", new CompoundTag());

        ProgressStore loaded = ProgressStore.load(tag, null);
        assertEquals(1, loaded.team(TEAM).shared.completed.size());
        assertEquals("test:book/node_0", loaded.team(TEAM).shared.completed.iterator().next());
    }

    /** A version 1 file (one string per player) still upgrades on load. */
    @Test
    void aVersionOneFileStillUpgrades() {
        CompoundTag tag = new CompoundTag();
        CompoundTag players = new CompoundTag();
        UUID solo = player(0);
        players.putString(solo.toString(), "{\"completed\":[\"test:book/node_0\"]}");
        tag.put("players", players);

        ProgressStore loaded = ProgressStore.load(tag, null);
        assertEquals(1, loaded.team(solo).shared.completed.size());
    }
}
