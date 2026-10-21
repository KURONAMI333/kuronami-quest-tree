package com.kuronami.questtree.progress;

import java.util.UUID;

/**
 * Whether a pickup counts as progress. An item the player threw themselves does not, so a pickup task cannot be
 * farmed by dropping and taking the same stack (2026-10-21). Crafting and uncrafting in a loop is not detected; that
 * limit is written in the data model notes.
 */
public final class PickupRule {

    private PickupRule() {
    }

    public static boolean counts(UUID owner, UUID picker) {
        return owner == null || !owner.equals(picker);
    }
}
