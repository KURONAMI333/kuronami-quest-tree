package com.kuronami.questtree.progress;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Who receives a progress event: shared actions go to the team, a reward toast only to the player who took it. */
public final class ProgressEvents {

    private ProgressEvents() {
    }

    public static Set<UUID> recipients(String kind, UUID actor, Set<UUID> members) {
        if (!kind.equals("reward")) {
            return members;
        }
        Set<UUID> one = new LinkedHashSet<>();
        one.add(actor);
        return one;
    }
}
