package com.kuronami.questtree.server;

import com.kuronami.questtree.progress.TeamProgress;
import com.kuronami.questtree.progress.TeamStoreData;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An editor publish can move a reward index (reorder, insert, delete), and the claim is stored by that index. The
 * publish path must move the saved claim before it sends the new book: a client that still holds the old index reads
 * the reward at the old place, so the tick in between could hand the same reward out twice (2026-10-22 release
 * review). The tick itself needs a running server; this holds the step it calls.
 */
class EditorPublishRemapTest {

    private static final UUID TEAM = UUID.fromString("00000000-0000-0000-0000-0000000000fd");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static ProgressStore store() {
        TeamStoreData data = new TeamStoreData();
        TeamProgress team = data.team(TEAM);
        data.owners.put(MEMBER, TEAM);
        team.shared.claimedRewards.add("t:basics/a#1");
        team.part(MEMBER).pendingRewards.put("t:basics/a#0#1", 1);
        ProgressStore store = new ProgressStore();
        store.replaceData(data);
        return store;
    }

    @Test
    void aPublishMovesTheSavedClaimBeforeTheBookIsSent() {
        ProgressStore store = store();

        assertTrue(EditorService.applyRewardRemap(store, Map.of("t:basics/a", Map.of(1, 0))),
                "a claim moved, so the progress has to be sent again");

        TeamProgress team = store.team(TEAM);
        assertTrue(team.shared.claimedRewards.contains("t:basics/a#0"));
        assertFalse(team.shared.claimedRewards.contains("t:basics/a#1"));
        assertTrue(team.players.get(MEMBER).pendingRewards.containsKey("t:basics/a#0#0"),
                "a pending reward's index moves the same way");
    }

    @Test
    void aPublishThatMovesNothingNeedsNoExtraSend() {
        assertFalse(EditorService.applyRewardRemap(store(), Map.of()));
        assertFalse(EditorService.applyRewardRemap(store(), Map.of("t:basics/a", Map.of(1, 1))),
                "an index that did not move is not a change");
    }
}
