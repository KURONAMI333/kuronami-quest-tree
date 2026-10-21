package com.kuronami.questtree.progress;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProgressEventsTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void rewardToastsOnlyReachThePlayerWhoTookTheReward() {
        Set<UUID> members = new LinkedHashSet<>(Set.of(A, B));
        assertEquals(Set.of(A), ProgressEvents.recipients("reward", A, members));
        assertEquals(Set.of(A, B), ProgressEvents.recipients("complete", A, members));
    }
}
