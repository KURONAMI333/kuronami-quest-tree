package com.kuronami.questtree.server;

import com.kuronami.questtree.progress.EquipmentPoll;
import com.kuronami.questtree.progress.PlayerProgress;
import com.kuronami.questtree.progress.RestrictionRules;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Enforces the loaded {@link RestrictionRules} (DATA_MODEL.md "制限"). Every check is server-side and silent for a
 * rule whose conditions hold; an unmet rule refuses the action and plays the lock cue at most once a second per player,
 * so holding the button down does not repeat the sound. The reason is on the item's tooltip, not in chat.
 * <p>
 * The interaction mixins call {@link #blockedItem} and {@link #blockedBlock} before the action runs. Equipment is
 * checked every few ticks per player instead of through an equip event: Fabric 1.21.1 has no equipment-change
 * callback, a UUID spreads the players' checks over the interval, and an unchanged signature skips the rules and
 * their conditions (2026-10-22 review, L1). The item and block tags the rules name are turned into {@link TagKey}s
 * once per load.
 */
public final class Restrictions {

    /** How long one reason stays up before the next refusal may replace it. */
    private static final long NOTICE_INTERVAL_MS = 1000L;

    private static final EquipmentSlot[] WORN = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND
    };

    private static final Map<UUID, Long> LAST_NOTICE = new HashMap<>();
    /** The last equipment signature checked, per player. */
    private static final Map<UUID, Long> SEEN = new HashMap<>();
    /** Tag keys for the loaded rules, made when the data is published, not while a player is ticking. */
    private static Map<String, TagKey<Item>> itemTags = Map.of();
    private static Map<String, TagKey<Block>> blockTags = Map.of();
    /** Every item and block id each rule covers, tags expanded once per load, for the tooltip hints. */
    private static Map<String, List<String>> hintIds = Map.of();

    private Restrictions() {
    }

    /** The rules were (re)loaded: turn every tag they name into a key once. */
    public static void reload(RestrictionRules rules) {
        Map<String, TagKey<Item>> items = new LinkedHashMap<>();
        Map<String, TagKey<Block>> blocks = new LinkedHashMap<>();
        for (RestrictionRules.Rule rule : rules.all()) {
            for (String tag : rule.itemTags()) {
                items.computeIfAbsent(tag, t -> TagKey.create(Registries.ITEM, ResourceLocation.parse(t)));
            }
            for (String tag : rule.blockTags()) {
                blocks.computeIfAbsent(tag, t -> TagKey.create(Registries.BLOCK, ResourceLocation.parse(t)));
            }
        }
        Map<String, List<String>> hints = new LinkedHashMap<>();
        for (RestrictionRules.Rule rule : rules.all()) {
            LinkedHashSet<String> ids = new LinkedHashSet<>(rule.items());
            ids.addAll(rule.blocks());
            for (String tag : rule.itemTags()) {
                BuiltInRegistries.ITEM.getTag(itemTag(tag)).ifPresent(set -> set.forEach(holder ->
                        holder.unwrapKey().ifPresent(key -> ids.add(key.location().toString()))));
            }
            for (String tag : rule.blockTags()) {
                BuiltInRegistries.BLOCK.getTag(blockTag(tag)).ifPresent(set -> set.forEach(holder ->
                        holder.unwrapKey().ifPresent(key -> ids.add(key.location().toString()))));
            }
            hints.put(rule.id(), List.copyOf(ids));
        }
        itemTags = Map.copyOf(items);
        blockTags = Map.copyOf(blocks);
        hintIds = Map.copyOf(hints);
        SEEN.clear();
    }

    /**
     * The item and block ids the player's unmet restrictions cover, encoded as {@code id|reason} for the progress
     * packet. The client puts the first reason on the item's tooltip, so the requirement stays visible without text
     * in chat or the action bar (2026-10-22).
     */
    public static List<String> lockedHints(ServerPlayer player) {
        RestrictionRules rules = ProgressData.restrictions();
        if (rules.isEmpty()) {
            return List.of();
        }
        PlayerProgress progress = ProgressService.progress(player);
        Map<String, String> byId = new LinkedHashMap<>();
        for (RestrictionRules.Rule rule : rules.all()) {
            if (ProgressService.conditionsMet(player, progress, null, rule.json(), true)) {
                continue;
            }
            String message = rule.message().isBlank() ? ""
                    : rule.message().replace('\n', ' ').replace('|', '/');
            if (message.length() > 256) {
                message = message.substring(0, 256);
            }
            for (String id : hintIds.getOrDefault(rule.id(), List.of())) {
                byId.putIfAbsent(id, message);
            }
        }
        List<String> out = new ArrayList<>(byId.size());
        byId.forEach((id, message) -> out.add(id + "|" + message));
        return List.copyOf(out);
    }

    private static TagKey<Item> itemTag(String tag) {
        TagKey<Item> key = itemTags.get(tag);
        return key != null ? key : TagKey.create(Registries.ITEM, ResourceLocation.parse(tag));
    }

    private static TagKey<Block> blockTag(String tag) {
        TagKey<Block> key = blockTags.get(tag);
        return key != null ? key : TagKey.create(Registries.BLOCK, ResourceLocation.parse(tag));
    }

    /** Whether using (or attacking with, placing) this stack is refused for the player. */
    public static boolean blockedItem(ServerPlayer player, ItemStack stack, String action) {
        if (stack == null || stack.isEmpty() || player.isSpectator() || ServerPlayers.isFake(player)) {
            return false;
        }
        RestrictionRules rules = ProgressData.restrictions();
        if (rules.isEmpty()) {
            return false;
        }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        List<RestrictionRules.Rule> hits = rules.forItem(action, id, tag -> stack.is(itemTag(tag)));
        return refuse(player, hits);
    }

    /**
     * Whether placing this stack is refused: either the item itself is restricted for {@code place}, or the block it
     * would become is. The block state is the default one, because tags belong to the block, not to its facing.
     */
    public static boolean blockedPlacement(ServerPlayer player, ItemStack stack) {
        if (blockedItem(player, stack, RestrictionRules.PLACE)) {
            return true;
        }
        return stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem
                && blockedBlock(player, blockItem.getBlock().defaultBlockState(), RestrictionRules.PLACE);
    }

    /** Whether using, placing or breaking this block is refused for the player. */
    public static boolean blockedBlock(ServerPlayer player, BlockState state, String action) {
        if (state == null || player.isSpectator() || ServerPlayers.isFake(player)) {
            return false;
        }
        RestrictionRules rules = ProgressData.restrictions();
        if (rules.isEmpty()) {
            return false;
        }
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        List<RestrictionRules.Rule> hits = rules.forBlock(action, id, tag -> state.is(blockTag(tag)));
        return refuse(player, hits);
    }

    /** Whether breaking this block is refused, by the block or by the item held to break it. */
    public static boolean blockedBreak(ServerPlayer player, BlockState state) {
        return blockedBlock(player, state, RestrictionRules.BREAK)
                || blockedItem(player, player.getMainHandItem(), RestrictionRules.BREAK);
    }

    /**
     * Puts back a worn or offhand item that a rule now refuses. Called from the player tick; the tick itself is
     * skipped unless it is this player's turn and the worn items, the rules and the progress revision all match the
     * last check, so a player with nothing restricted costs almost nothing.
     */
    public static void tick(ServerPlayer player) {
        if (ServerPlayers.isFake(player) || !EquipmentPoll.due(player.server.getTickCount(), player.getUUID())) {
            return;
        }
        RestrictionRules rules = ProgressData.restrictions();
        if (rules.isEmpty() || player.isSpectator()) {
            return;
        }
        List<String> wornIds = new ArrayList<>(WORN.length);
        for (EquipmentSlot slot : WORN) {
            ItemStack worn = player.getItemBySlot(slot);
            wornIds.add(worn.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(worn.getItem()).toString());
        }
        long signature = EquipmentPoll.signature(wornIds, System.identityHashCode(rules),
                ProgressService.revision(player.getUUID()));
        if (Long.valueOf(signature).equals(SEEN.put(player.getUUID(), signature))) {
            return;
        }
        for (EquipmentSlot slot : WORN) {
            ItemStack worn = player.getItemBySlot(slot);
            if (worn.isEmpty()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(worn.getItem()).toString();
            List<RestrictionRules.Rule> hits = rules.forItem(RestrictionRules.EQUIP, id,
                    tag -> worn.is(itemTag(tag)));
            if (!refuse(player, hits)) {
                continue;
            }
            ItemStack taken = worn.copy();
            player.setItemSlot(slot, ItemStack.EMPTY);
            if (!player.getInventory().add(taken)) {
                player.drop(taken, false);
            }
        }
    }

    /** The player logged out: the next session starts with fresh notice and equipment caches. */
    public static void forget(UUID player) {
        LAST_NOTICE.remove(player);
        SEEN.remove(player);
    }

    private static boolean refuse(ServerPlayer player, List<RestrictionRules.Rule> hits) {
        if (hits.isEmpty()) {
            return false;
        }
        PlayerProgress progress = null;
        for (RestrictionRules.Rule rule : hits) {
            if (progress == null) {
                progress = ProgressService.progress(player);
            }
            if (!ProgressService.conditionsMet(player, progress, null, rule.json(), true)) {
                notice(player);
                return true;
            }
        }
        return false;
    }

    /** Plays the lock cue, at most once a second; the reason is on the item's tooltip. */
    private static void notice(ServerPlayer player) {
        long now = System.currentTimeMillis();
        Long last = LAST_NOTICE.get(player.getUUID());
        if (last != null && now - last < NOTICE_INTERVAL_MS) {
            return;
        }
        LAST_NOTICE.put(player.getUUID(), now);
        Cues.restricted(player);
    }
}
