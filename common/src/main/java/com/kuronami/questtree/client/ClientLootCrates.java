package com.kuronami.questtree.client;

import com.google.gson.JsonObject;
import com.kuronami.questtree.item.ModDataComponents;
import com.kuronami.questtree.progress.LootCrateRules;
import com.kuronami.questtree.progress.ProgressRules;
import net.minecraft.world.item.ItemStack;

/** The crate's pack-defined colour, read when the item is drawn. */
public final class ClientLootCrates {

    private ClientLootCrates() {
    }

    public static int color(ItemStack stack) {
        var component = ModDataComponents.lootCrate();
        String tableId = stack == null || component == null ? null : stack.get(component);
        if (tableId == null || tableId.isEmpty()) {
            return 0xFFFFFF;
        }
        ProgressRules rules = ClientProgress.rules();
        JsonObject table = rules == null ? null : rules.model().rewardTables.get(tableId);
        return LootCrateRules.crate(tableId, table).map(LootCrateRules.Crate::color).orElse(0xFFFFFF);
    }
}
