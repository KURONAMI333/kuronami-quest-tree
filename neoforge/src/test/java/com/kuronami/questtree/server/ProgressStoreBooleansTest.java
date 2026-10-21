package com.kuronami.questtree.server;

import com.kuronami.questtree.progress.PlayerPart;
import com.kuronami.questtree.progress.TeamProgress;
import com.kuronami.questtree.progress.TeamStoreData;
import com.mojang.serialization.JsonOps;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every boolean in a team record must come back with the value it was saved with. The previous build wrote a boolean
 * as the byte 1 and read the byte back as the number 1, so all four flags were true after any restart: the book was
 * handed out on every login, an admin's block on a reward lifted itself, a closed chapter opened, and the emergency
 * wait notice showed again (2026-10-22 release review).
 */
class ProgressStoreBooleansTest {

    private static final UUID TEAM = UUID.fromString("00000000-0000-0000-0000-0000000000fe");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void trueBooleansSurviveASaveAndALoad() {
        assertSurvivesASaveAndALoad(true);
    }

    @Test
    void falseBooleansSurviveASaveAndALoad() {
        assertSurvivesASaveAndALoad(false);
    }

    private static void assertSurvivesASaveAndALoad(boolean value) {
        TeamStoreData data = new TeamStoreData();
        TeamProgress team = data.team(TEAM);
        data.owners.put(MEMBER, TEAM);
        PlayerPart part = team.part(MEMBER);
        part.bookGiven = value;
        part.rewardsBlocked = value;
        part.emergencyWaitNotice = value;
        team.shared.chapterOpen.put("test:chapter", value);

        ProgressStore store = new ProgressStore();
        store.replaceData(data);
        CompoundTag tag = store.save(new CompoundTag(), null);

        TeamProgress loaded = ProgressStore.load(tag, null).team(TEAM);
        PlayerPart back = loaded.players.get(MEMBER);
        assertEquals(value, back.bookGiven, "book_given");
        assertEquals(value, back.rewardsBlocked, "rewards_blocked");
        assertEquals(value, back.emergencyWaitNotice, "emergency_wait_notice");
        assertEquals(Boolean.valueOf(value), loaded.shared.chapterOpen.get("test:chapter"), "chapter_open");

        ProgressStore reloaded = ProgressStore.load(
                ProgressStore.load(tag, null).save(new CompoundTag(), null), null);
        TeamProgress again = reloaded.team(TEAM);
        assertEquals(value, again.players.get(MEMBER).bookGiven, "a second save keeps the value");
        assertEquals(Boolean.valueOf(value), again.shared.chapterOpen.get("test:chapter"));
    }

    /**
     * A save from the previous build holds booleans as bytes and numbers as narrowly as NBT allowed, so a counter of
     * 1 or 0 is a byte too. Both still load with their meaning intact.
     */
    @Test
    void aSaveFromThePreviousBuildStillLoads() {
        TeamStoreData data = new TeamStoreData();
        TeamProgress team = data.team(TEAM);
        data.owners.put(MEMBER, TEAM);
        PlayerPart part = team.part(MEMBER);
        part.bookGiven = true;
        part.rewardsBlocked = true;
        part.emergencyWaitNotice = true;
        team.shared.chapterOpen.put("test:chapter", true);
        part.completed.add("test:book/one");
        part.tasks.put("test:book/one", 1L);
        part.tasks.put("test:book/big", 300L);
        part.repeats.put("test:book/one", 1);
        part.cooldown.put("test:book/none", 0L);
        part.pendingRewards.put("test:book/one#0", 1);
        part.completedAt.put("test:book/one", 1_700_000_000_000L);

        // the conversion the previous build saved through
        Tag legacy = JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, team.toJson());
        CompoundTag tag = new CompoundTag();
        tag.putInt("version", TeamStoreData.VERSION);
        CompoundTag teams = new CompoundTag();
        teams.put(TEAM.toString(), legacy);
        tag.put("teams", teams);
        CompoundTag owners = new CompoundTag();
        owners.putUUID(MEMBER.toString(), TEAM);
        tag.put("owners", owners);
        CompoundTag migrations = new CompoundTag();
        tag.put("migrations", migrations);

        TeamProgress loaded = ProgressStore.load(tag, null).team(TEAM);
        PlayerPart back = loaded.players.get(MEMBER);

        assertTrue(back.bookGiven, "a byte 1 is still true");
        assertTrue(back.rewardsBlocked);
        assertTrue(back.emergencyWaitNotice);
        assertTrue(loaded.shared.chapterOpen.get("test:chapter"), "a byte 1 chapter state is still open");
        assertEquals(1L, back.tasks.get("test:book/one"), "a byte 1 counter is the number 1");
        assertEquals(300L, back.tasks.get("test:book/big"), "a narrowed number keeps its value");
        assertEquals(1, back.repeats.get("test:book/one"));
        assertEquals(0L, back.cooldown.get("test:book/none"), "a byte 0 counter is the number 0, not false");
        assertEquals(1, back.pendingRewards.get("test:book/one#0"));
        assertEquals(1_700_000_000_000L, back.completedAt.get("test:book/one"));
        assertTrue(back.completed.contains("test:book/one"));
    }
}
