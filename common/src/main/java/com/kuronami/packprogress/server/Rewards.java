package com.kuronami.packprogress.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kuronami.packprogress.Constants;
import com.kuronami.packprogress.data.ProgressModel;
import com.kuronami.packprogress.progress.PlayerProgress;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Hands out rewards (DATA_MODEL.md "報酬"). Every reward is checked again here; nothing trusts the client. */
public final class Rewards {

    private Rewards() {
    }

    static void give(ServerPlayer player, PlayerProgress p, ProgressModel.Node n, JsonObject rw, int choice) {
        try {
            switch (ProgressService.type(rw)) {
                case "item" -> item(player, rw);
                case "xp" -> player.giveExperiencePoints(intOf(rw, "amount", 0));
                case "xp_levels" -> player.giveExperienceLevels(intOf(rw, "amount", 0));
                case "command" -> command(player, rw);
                case "table" -> table(player, p, n, rw, choice);
                case "advancement" -> advancement(player, rw);
                case "stage" -> ServerCommands.run(player, "pack_stages " + (ProgressModel.boolOf(rw, "remove") ? "remove " : "add ")
                        + player.getGameProfile().getName() + " " + ProgressService.str(rw, "stage"), 2, true);
                case "attribute" -> attribute(player, n.key, slotOf(n, rw), rw);
                case "points" -> p.bonus.merge(pointsChapter(n, rw), intOf(rw, "amount", 1), Integer::sum);
                case "tag" -> player.addTag(ProgressService.str(rw, "tag"));
                case "toast" -> ProgressService.event(player, p, "toast", n.key);
                default -> Constants.LOG.warn("Reward type '{}' of {} is not supported yet", ProgressService.type(rw), n.key);
            }
        } catch (RuntimeException ex) {
            Constants.LOG.error("Reward of {} failed: {}", n.key, ex.toString());
        }
    }

    /** Rewards of a skill node when it is taken: the node's own rewards, or the rewards of rank {@code rank}. */
    static void giveSkill(ServerPlayer player, PlayerProgress p, ProgressModel.Node n, int rank) {
        List<JsonObject> list = rewardsOfRank(n, rank);
        int i = 0;
        for (JsonObject rw : list) {
            if (ProgressService.type(rw).equals("attribute")) {
                attribute(player, n.key, rank * 100 + i, rw);
            } else {
                give(player, p, n, rw, -1);
            }
            i++;
        }
    }

    /** Index of a quest reward, so two attribute rewards of one quest get separate modifiers (1000+ keeps clear of skill ranks). */
    private static int slotOf(ProgressModel.Node n, JsonObject rw) {
        for (int i = 0; i < n.rewards.size(); i++) {
            if (n.rewards.get(i) == rw) {
                return 1000 + i;
            }
        }
        return 1000;
    }

    /** Number of entries of the table a table reward points at (0 when missing). */
    static int tableSize(JsonObject rw) {
        JsonObject table = ProgressService.rules().model().rewardTables.get(ProgressService.str(rw, "table"));
        return table == null || !table.has("entries") ? 0 : table.getAsJsonArray("entries").size();
    }

    /**
     * Puts back every attribute modifier the player earned: taken skills (each rank so far) and claimed quests. Used on
     * join and respawn, because respawning copies only base values. Fixed modifier ids make repeating it harmless.
     */
    static void reapplyAttributes(ServerPlayer player, PlayerProgress p) {
        var model = ProgressService.rules().model();
        for (var e : p.taken.entrySet()) {
            ProgressModel.Node n = model.nodes.get(e.getKey());
            if (n != null) {
                for (int rank = 0; rank < e.getValue(); rank++) {
                    List<JsonObject> list = rewardsOfRank(n, rank);
                    for (int i = 0; i < list.size(); i++) {
                        if (ProgressService.type(list.get(i)).equals("attribute")) {
                            attribute(player, n.key, rank * 100 + i, list.get(i));
                        }
                    }
                }
            }
        }
        // claimed quests, and repeatable quests claimed before (a repeat clears "claimed")
        java.util.Set<String> claimedOnce = new java.util.HashSet<>(p.claimed);
        p.repeats.forEach((k, v) -> {
            if (v > 0) {
                claimedOnce.add(k);
            }
        });
        for (String key : claimedOnce) {
            ProgressModel.Node n = model.nodes.get(key);
            if (n != null) {
                for (JsonObject rw : n.rewards) {
                    if (ProgressService.type(rw).equals("attribute")) {
                        attribute(player, n.key, slotOf(n, rw), rw);
                    }
                }
            }
        }
    }

    /** Removes the attribute modifiers a node gave (all ranks, quest rewards), for resets and respec. */
    static void removeAttributes(ServerPlayer player, ProgressModel.Node n) {
        int ranks = Math.max(1, n.ranks);
        for (int rank = 0; rank < ranks; rank++) {
            List<JsonObject> list = rewardsOfRank(n, rank);
            for (int i = 0; i < list.size(); i++) {
                removeAttribute(player, list.get(i), modifierId(n.key, rank * 100 + i));
            }
        }
        for (int i = 0; i < n.rewards.size(); i++) {
            removeAttribute(player, n.rewards.get(i), modifierId(n.key, 1000 + i));
        }
    }

    private static void removeAttribute(ServerPlayer player, JsonObject rw, ResourceLocation id) {
        if (!ProgressService.type(rw).equals("attribute")) {
            return;
        }
        ResourceLocation rl = ResourceLocation.tryParse(ProgressService.str(rw, "attribute"));
        Holder<Attribute> attr = rl == null ? null : BuiltInRegistries.ATTRIBUTE.getHolder(rl).orElse(null);
        AttributeInstance inst = attr == null ? null : player.getAttribute(attr);
        if (inst != null) {
            inst.removeModifier(id);
        }
    }

    private static List<JsonObject> rewardsOfRank(ProgressModel.Node n, int rank) {
        if (n.ranks > 0 && n.json.has("ranks")) {
            JsonElement r = n.json.getAsJsonArray("ranks").get(Math.min(rank, n.ranks - 1));
            List<JsonObject> list = new ArrayList<>();
            if (r.isJsonObject() && r.getAsJsonObject().has("rewards")) {
                for (JsonElement e : r.getAsJsonObject().getAsJsonArray("rewards")) {
                    list.add(e.getAsJsonObject());
                }
            }
            return list;
        }
        return n.rewards;
    }

    private static void item(ServerPlayer player, JsonObject rw) {
        JsonElement it = rw.get("item");
        String id;
        int count = 1;
        if (it == null) {
            return;
        }
        if (it.isJsonPrimitive()) {
            id = it.getAsString();
        } else {
            JsonObject o = it.getAsJsonObject();
            id = o.has("id") ? o.get("id").getAsString() : "";
            count = intOf(o, "count", 1);
        }
        int bonus = intOf(rw, "random_bonus", 0);
        if (bonus > 0) {
            count += player.getRandom().nextInt(bonus + 1);
        }
        ResourceLocation rl = ResourceLocation.tryParse(id);
        Item item = rl == null ? Items.AIR : BuiltInRegistries.ITEM.get(rl);
        if (item == Items.AIR) {
            Constants.LOG.warn("Reward item '{}' does not exist", id);
            return;
        }
        int max = new ItemStack(item).getMaxStackSize();
        while (count > 0) {
            ItemStack st = new ItemStack(item, Math.min(count, max));
            count -= st.getCount();
            if (!player.getInventory().add(st) || !st.isEmpty()) {
                player.drop(st, false);
            }
        }
    }

    private static void command(ServerPlayer player, JsonObject rw) {
        String cmd = ProgressService.str(rw, "command");
        if (cmd.isEmpty()) {
            return;
        }
        // FTB Quests writes the player as {p}; Pufferfish's Skills commands run as the player (@s)
        cmd = cmd.replace("{p}", player.getGameProfile().getName());
        // the data pack author decides what runs, as with FTB Quests and Pufferfish's Skills; level 2 unless the data says otherwise
        int level = intOf(rw, "permission_level", 2);
        ServerCommands.run(player, cmd, level, !rw.has("silent") || ProgressModel.boolOf(rw, "silent"));
    }

    private static void table(ServerPlayer player, PlayerProgress p, ProgressModel.Node n, JsonObject rw, int choice) {
        JsonObject table = ProgressService.rules().model().rewardTables.get(ProgressService.str(rw, "table"));
        if (table == null || !table.has("entries")) {
            Constants.LOG.warn("Reward table '{}' of {} does not exist", ProgressService.str(rw, "table"), n.key);
            return;
        }
        List<JsonObject> entries = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        for (JsonElement e : table.getAsJsonArray("entries")) {
            JsonObject o = e.getAsJsonObject();
            entries.add(o.getAsJsonObject("reward"));
            weights.add(o.has("weight") ? o.get("weight").getAsDouble() : 1.0);
        }
        String mode = rw.has("mode") ? rw.get("mode").getAsString() : "random";
        switch (mode) {
            case "all" -> entries.forEach(e -> give(player, p, n, e, -1));
            case "choice" -> {
                if (choice >= 0 && choice < entries.size()) {
                    give(player, p, n, entries.get(choice), -1);
                }
            }
            default -> {
                int rolls = intOf(table, "rolls", 1);
                double empty = table.has("empty_weight") ? table.get("empty_weight").getAsDouble() : 0;
                double total = empty + weights.stream().mapToDouble(Double::doubleValue).sum();
                for (int r = 0; r < rolls && total > 0; r++) {
                    double x = player.getRandom().nextDouble() * total - empty;
                    for (int i = 0; i < entries.size() && x >= 0; i++) {
                        x -= weights.get(i);
                        if (x < 0) {
                            give(player, p, n, entries.get(i), -1);
                        }
                    }
                }
            }
        }
    }

    private static void advancement(ServerPlayer player, JsonObject rw) {
        ResourceLocation rl = ResourceLocation.tryParse(ProgressService.str(rw, "advancement"));
        AdvancementHolder h = rl == null ? null : player.server.getAdvancements().get(rl);
        if (h == null) {
            return;
        }
        String crit = ProgressService.str(rw, "criterion");
        AdvancementProgress prog = player.getAdvancements().getOrStartProgress(h);
        if (!crit.isEmpty()) {
            player.getAdvancements().award(h, crit);
            return;
        }
        for (String c : prog.getRemainingCriteria()) {
            player.getAdvancements().award(h, c);
        }
    }

    /**
     * Permanent attribute modifier with a fixed id per node and slot, so taking the same skill twice (or reloading)
     * replaces instead of stacking.
     */
    static void attribute(ServerPlayer player, String nodeKey, int slot, JsonObject rw) {
        ResourceLocation rl = ResourceLocation.tryParse(ProgressService.str(rw, "attribute"));
        Holder<Attribute> attr = rl == null ? null : BuiltInRegistries.ATTRIBUTE.getHolder(rl).orElse(null);
        if (attr == null) {
            Constants.LOG.warn("Attribute '{}' does not exist", ProgressService.str(rw, "attribute"));
            return;
        }
        AttributeInstance inst = player.getAttribute(attr);
        if (inst == null) {
            return;
        }
        ResourceLocation id = modifierId(nodeKey, slot);
        double value = rw.has("value") ? rw.get("value").getAsDouble() : 0;
        inst.removeModifier(id);
        inst.addPermanentModifier(new AttributeModifier(id, value, operation(ProgressService.str(rw, "operation"))));
    }

    static ResourceLocation modifierId(String nodeKey, int slot) {
        String path = (nodeKey.replace(':', '/') + "/" + slot).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]", "_");
        return ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, path);
    }

    private static AttributeModifier.Operation operation(String s) {
        return switch (s) {
            case "multiply_base", "addition_multiplied_base", "add_multiplied_base" -> AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
            case "multiply_total", "add_multiplied_total" -> AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
            default -> AttributeModifier.Operation.ADD_VALUE;
        };
    }

    private static String pointsChapter(ProgressModel.Node n, JsonObject rw) {
        String c = ProgressService.str(rw, "chapter");
        return c.isEmpty() ? n.chapter.id : c;
    }

    private static int intOf(JsonObject o, String k, int def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? (int) o.get(k).getAsDouble() : def;
    }
}
