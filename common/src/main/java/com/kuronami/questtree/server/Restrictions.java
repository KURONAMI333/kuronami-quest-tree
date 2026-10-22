package com.kuronami.questtree.server;

import com.kuronami.questtree.progress.PlayerProgress;
import com.kuronami.questtree.progress.RestrictionRules;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Enforces the loaded {@link RestrictionRules} (DATA_MODEL.md "制限"). Every check is server-side and silent for a
 * rule whose conditions hold; an unmet rule refuses the action and shows the pack's reason in the action bar, at most
 * once a second per player, so holding the button down does not flood the chat.
 * <p>
 * The interaction mixins call {@link #blockedItem} and {@link #blockedBlock} before the action runs. Equipment is
 * checked once per player tick instead of through an equip event: Fabric 1.21.1 has no equipment-change callback, and
 * a worn item that should not be worn is put back the same tick.
 */
public final class Restrictions {

    /** How long one reason stays up before the next refusal may replace it. */
    private static final long NOTICE_INTERVAL_MS = 1000L;

    private static final EquipmentSlot[] WORN = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND
    };

    private static final Map<UUID, Long> LAST_NOTICE = new HashMap<>();

    private Restrictions() {
    }

    /** Whether using (or attacking with, placing) this stack is refused for the player. */
    public static boolean blockedItem(ServerPlayer player, ItemStack stack, String action) {
        if (stack == null || stack.isEmpty() || player.isSpectator()) {
            return false;
        }
        RestrictionRules rules = ProgressData.restrictions();
        if (rules.isEmpty()) {
            return false;
        }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        List<RestrictionRules.Rule> hits = rules.forItem(action, id,
                tag -> stack.is(TagKey.create(Registries.ITEM, ResourceLocation.parse(tag))));
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
        if (state == null || player.isSpectator()) {
            return false;
        }
        RestrictionRules rules = ProgressData.restrictions();
        if (rules.isEmpty()) {
            return false;
        }
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        List<RestrictionRules.Rule> hits = rules.forBlock(action, id,
                tag -> state.is(TagKey.create(Registries.BLOCK, ResourceLocation.parse(tag))));
        return refuse(player, hits);
    }

    /** Whether breaking this block is refused, by the block or by the item held to break it. */
    public static boolean blockedBreak(ServerPlayer player, BlockState state) {
        return blockedBlock(player, state, RestrictionRules.BREAK)
                || blockedItem(player, player.getMainHandItem(), RestrictionRules.BREAK);
    }

    /**
     * Puts back a worn or offhand item that a rule now refuses. Called once per player tick: moving the stack fires
     * no second check because the slot is empty afterwards.
     */
    public static void tick(ServerPlayer player) {
        RestrictionRules rules = ProgressData.restrictions();
        if (rules.isEmpty() || player.isSpectator()) {
            return;
        }
        for (EquipmentSlot slot : WORN) {
            ItemStack worn = player.getItemBySlot(slot);
            if (worn.isEmpty()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(worn.getItem()).toString();
            List<RestrictionRules.Rule> hits = rules.forItem(RestrictionRules.EQUIP, id,
                    tag -> worn.is(TagKey.create(Registries.ITEM, ResourceLocation.parse(tag))));
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

    /** The player logged out: the next session starts with a fresh notice window. */
    public static void forget(UUID player) {
        LAST_NOTICE.remove(player);
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
                notice(player, rule);
                return true;
            }
        }
        return false;
    }

    /** Shows the rule's own reason, or a generic one when the pack wrote none. */
    private static void notice(ServerPlayer player, RestrictionRules.Rule rule) {
        long now = System.currentTimeMillis();
        Long last = LAST_NOTICE.get(player.getUUID());
        if (last != null && now - last < NOTICE_INTERVAL_MS) {
            return;
        }
        LAST_NOTICE.put(player.getUUID(), now);
        String message = rule.message();
        player.displayClientMessage(message.isBlank()
                ? Component.translatable("message.kuronami_quest_tree.restricted")
                : Component.literal(message), true);
    }
}
