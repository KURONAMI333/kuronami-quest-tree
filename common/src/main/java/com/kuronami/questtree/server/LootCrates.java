package com.kuronami.questtree.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kuronami.questtree.data.ProgressModel;
import com.kuronami.questtree.item.ModItems;
import com.kuronami.questtree.network.CrateLootPayload;
import com.kuronami.questtree.platform.Services;
import com.kuronami.questtree.progress.LootCrateRules;
import com.kuronami.questtree.progress.PlayerProgress;
import com.kuronami.questtree.progress.RewardRoll;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.ElderGuardian;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loot crates: opening a stack, and the rare crate a kill can drop (FTB Quests' loot crates, 2026-10-22).
 * The roll is the same {@link RewardRoll} the rest of the reward tables use, with the empty weight included and
 * {@code rolls} multiplied by the number of crates opened.
 */
public final class LootCrates {

    /** FTB Quests' opt-out tag, so an imported pack's exclusions keep working. */
    public static final TagKey<EntityType<?>> NO_LOOT_CRATES =
            TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath("ftbquests", "no_loot_crates"));
    /** Kuronami's own name for the same thing. */
    public static final TagKey<EntityType<?>> OWN_NO_LOOT_CRATES =
            TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath("kuronami_quest_tree", "no_loot_crates"));

    private LootCrates() {
    }

    /** Opens {@code count} crates of one table; false when the table has no crate or cannot be read. */
    public static boolean open(ServerPlayer player, String tableId, int count) {
        List<JsonObject> rewards = roll(player, tableId, count);
        if (rewards == null) {
            return false;
        }
        PlayerProgress progress = ProgressService.progress(player);
        List<ItemStack> shown = new ArrayList<>();
        for (JsonObject reward : rewards) {
            Rewards.crateEntry(player, progress, reward, tableId);
            ItemStack preview = preview(reward);
            if (!preview.isEmpty()) {
                shown.add(preview);
            }
        }
        if (!shown.isEmpty()) {
            Services.PLATFORM.sendToPlayer(player, new CrateLootPayload(shown));
        }
        ProgressStore.get(player.server).setDirty();
        return true;
    }

    /** Whether the table exists and carries crate settings, so a crate put in would roll instead of vanishing. */
    public static boolean canRoll(String tableId) {
        if (tableId == null || tableId.isEmpty()) {
            return false;
        }
        ProgressModel model = ProgressService.rules().model();
        return model != null
                && LootCrateRules.crate(tableId, model.rewardTables.get(tableId)).isPresent();
    }

    /**
     * The reward entries {@code count} crates roll, or null when the table has no crate settings. Paying them out is
     * the caller's job, so an opener block can route items into its output instead of the owner's inventory.
     */
    public static List<JsonObject> roll(ServerPlayer player, String tableId, int count) {
        ProgressModel model = ProgressService.rules().model();
        JsonObject table = model.rewardTables.get(tableId);
        LootCrateRules.Crate crate = LootCrateRules.crate(tableId, table).orElse(null);
        if (table == null || crate == null || count <= 0) {
            return null;
        }
        List<JsonObject> entries = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        for (JsonElement e : entries(table)) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject entry = e.getAsJsonObject();
            if (!entry.has("reward") || !entry.get("reward").isJsonObject()) {
                continue;
            }
            entries.add(entry.getAsJsonObject("reward"));
            weights.add(entry.has("weight") && entry.get("weight").isJsonPrimitive()
                    && entry.get("weight").getAsJsonPrimitive().isNumber()
                    ? entry.get("weight").getAsDouble() : 1.0);
        }
        int rolls = Math.max(0, modelInt(table, "rolls", 1));
        long attempts = (long) rolls * count;
        if (attempts > 100_000L) {
            attempts = 100_000L;
        }
        double empty = table.has("empty_weight") && table.get("empty_weight").isJsonPrimitive()
                && table.get("empty_weight").getAsJsonPrimitive().isNumber()
                ? table.get("empty_weight").getAsDouble() : 0;
        List<Integer> picks = RewardRoll.roll(weights, empty, true, (int) attempts,
                () -> player.getRandom().nextDouble());
        List<JsonObject> out = new ArrayList<>();
        for (int pick : picks) {
            if (pick >= 0 && pick < entries.size()) {
                out.add(entries.get(pick));
            }
        }
        return out;
    }

    /**
     * One stack per table that has crate settings, for the creative tab: a pack's tables each keep their own name,
     * colour and glow, which a single generic crate cannot show. Empty before the pack has loaded.
     */
    public static List<ItemStack> creativeStacks() {
        List<ItemStack> out = new ArrayList<>();
        for (String id : ProgressData.model().rewardTables.keySet()) {
            ItemStack stack = stack(id);
            if (!stack.isEmpty()) {
                out.add(stack);
            }
        }
        return out;
    }

    /** A crate stack for {@code tableId}, or empty when the table has no crate settings. */
    public static ItemStack stack(String tableId) {
        JsonObject table = ProgressService.rules().model().rewardTables.get(tableId);
        LootCrateRules.Crate crate = LootCrateRules.crate(tableId, table).orElse(null);
        return crate == null ? ItemStack.EMPTY : ModItems.lootCrate(tableId, crate);
    }

    /** Gives a killing blow's crate drop, if this entity and this table's weights say so. */
    public static void onLivingDrops(ServerLevel level, LivingEntity entity, java.util.Collection<ItemEntity> drops) {
        String table = crateFor(entity, level);
        if (table == null) {
            return;
        }
        ItemStack stack = stack(table);
        if (stack.isEmpty()) {
            return;
        }
        ItemEntity item = new ItemEntity(level, entity.getX(), entity.getY(), entity.getZ(), stack);
        item.setPickUpDelay(10);
        drops.add(item);
    }

    /** For loaders without a drops collection: spawn the crate directly. */
    public static void spawnDrop(ServerLevel level, LivingEntity entity) {
        List<ItemEntity> drops = new ArrayList<>();
        onLivingDrops(level, entity, drops);
        for (ItemEntity drop : drops) {
            level.addFreshEntity(drop);
        }
    }

    /** The table whose crate this kill drops, or null. */
    public static String crateFor(LivingEntity entity, ServerLevel level) {
        if (entity instanceof ServerPlayer || entity.getType().is(NO_LOOT_CRATES)
                || entity.getType().is(OWN_NO_LOOT_CRATES)) {
            return null;
        }
        LootCrateRules.Settings settings = LootCrateRules.settings(ProgressData.settings());
        if (!settings.enabled()) {
            return null;
        }
        Map<String, LootCrateRules.Crate> crates = new LinkedHashMap<>();
        for (Map.Entry<String, JsonObject> e : ProgressService.rules().model().rewardTables.entrySet()) {
            LootCrateRules.crate(e.getKey(), e.getValue()).ifPresent(crate -> crates.put(e.getKey(), crate));
        }
        boolean boss = isBoss(entity);
        boolean monster = !boss && entity instanceof Monster;
        return LootCrateRules.choose(crates, settings, boss, monster, () -> level.random.nextDouble())
                .orElse(null);
    }

    /** FTB Quests' boss class: its own type tag first, then the vanilla bosses a pack may not tag. */
    private static boolean isBoss(LivingEntity entity) {
        if (entity.getType().is(TagKey.create(Registries.ENTITY_TYPE,
                ResourceLocation.fromNamespaceAndPath("c", "bosses")))) {
            return true;
        }
        return entity instanceof EnderDragon || entity instanceof WitherBoss
                || entity instanceof Warden || entity instanceof ElderGuardian;
    }

    private static List<JsonElement> entries(JsonObject table) {
        List<JsonElement> out = new ArrayList<>();
        if (table.has("entries") && table.get("entries").isJsonArray()) {
            for (JsonElement e : table.getAsJsonArray("entries")) {
                out.add(e);
            }
        }
        return out;
    }

    private static int modelInt(JsonObject table, String key, int fallback) {
        return table.has(key) && table.get(key).isJsonPrimitive() && table.get(key).getAsJsonPrimitive().isNumber()
                ? table.get(key).getAsInt() : fallback;
    }

    /** A stack for the notification screen; only entries that really are an item show their own icon. */
    private static ItemStack preview(JsonObject reward) {
        String type = ProgressService.type(reward);
        if (type.isEmpty() || type.equals("item")) {
            JsonElement it = reward.get("item");
            String id = "";
            int count = 1;
            if (it != null && it.isJsonPrimitive()) {
                id = it.getAsString();
            } else if (it != null && it.isJsonObject()) {
                JsonObject o = it.getAsJsonObject();
                id = o.has("id") && o.get("id").isJsonPrimitive() ? o.get("id").getAsString() : "";
                count = o.has("count") && o.get("count").isJsonPrimitive() ? o.get("count").getAsInt() : 1;
            }
            ResourceLocation rl = ResourceLocation.tryParse(id);
            Item item = rl == null ? Items.AIR : BuiltInRegistries.ITEM.get(rl);
            return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item, Math.max(1, Math.min(99, count)));
        }
        return switch (type) {
            case "xp", "xp_levels" -> new ItemStack(Items.EXPERIENCE_BOTTLE);
            case "currency" -> new ItemStack(Items.GOLD_INGOT);
            case "command" -> new ItemStack(Items.COMMAND_BLOCK);
            case "crate" -> stack(ProgressService.str(reward, "table"));
            default -> ItemStack.EMPTY;
        };
    }
}
