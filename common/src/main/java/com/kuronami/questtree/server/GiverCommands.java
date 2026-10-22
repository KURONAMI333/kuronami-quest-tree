package com.kuronami.questtree.server;

import com.kuronami.questtree.block.QuestTaskBlockEntity;
import com.kuronami.questtree.data.ProgressModel;
import com.kuronami.questtree.giver.GiverTarget;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.stream.Stream;

/**
 * {@code /quest_tree giver set <quest|chapter>} and {@code /quest_tree giver clear}: links the entity (or quest block)
 * the player is looking at to a quest or chapter, so a right click on it opens the book there (2026-10-22, G3).
 * <pre>
 * /quest_tree giver set aa_pack:basics/start
 * /quest_tree giver set aa_pack:basics
 * /quest_tree giver clear
 * </pre>
 * An entity link is saved by UUID in {@link GiverStore}; a block keeps its target in the block entity. Both need
 * permission level 2, and the source must be a player because the target is what that player is looking at.
 */
final class GiverCommands {

    private static final SuggestionProvider<CommandSourceStack> TARGETS = (c, b) -> {
        ProgressModel model = ProgressService.rules().model();
        return SharedSuggestionProvider.suggest(
                Stream.concat(model.chapters.keySet().stream(), model.nodes.keySet().stream()), b);
    };

    private GiverCommands() {
    }

    static LiteralCommandNode<CommandSourceStack> node() {
        return Commands.literal("giver")
                .requires(src -> com.kuronami.questtree.giver.GiverPolicy.mayManage(
                        src.hasPermission(com.kuronami.questtree.giver.GiverPolicy.PERMISSION),
                        src.getPlayer() != null))
                .then(Commands.literal("set")
                        .then(Commands.argument("target", StringArgumentType.word()).suggests(TARGETS)
                                .executes(GiverCommands::set)))
                .then(Commands.literal("clear").executes(GiverCommands::clear))
                .build();
    }

    private static int set(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(playerOnly());
            return 0;
        }
        String raw = StringArgumentType.getString(c, "target");
        GiverTarget target = GiverTarget.resolve(raw, ProgressService.rules().model());
        if (target == null) {
            src.sendFailure(Component.translatable("commands.kuronami_quest_tree.giver.unknown", raw));
            return 0;
        }
        Entity entity = lookedAtEntity(player);
        if (entity != null) {
            GiverStore.get(player.server).link(entity.getUUID(), target);
            src.sendSuccess(() -> Component.translatable("commands.kuronami_quest_tree.giver.set",
                    target.id(), entity.getDisplayName()), true);
            return 1;
        }
        QuestTaskBlockEntity block = lookedAtBlock(player);
        if (block != null) {
            block.setTarget(target);
            block.setChanged();
            player.serverLevel().sendBlockUpdated(block.getBlockPos(), block.getBlockState(),
                    block.getBlockState(), 3);
            src.sendSuccess(() -> Component.translatable("commands.kuronami_quest_tree.giver.set",
                    target.id(), Component.literal(block.getBlockPos().toShortString())), true);
            return 1;
        }
        src.sendFailure(Component.translatable("commands.kuronami_quest_tree.giver.no_target"));
        return 0;
    }

    private static int clear(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(playerOnly());
            return 0;
        }
        Entity entity = lookedAtEntity(player);
        if (entity != null) {
            if (!GiverStore.get(player.server).unlink(entity.getUUID())) {
                src.sendFailure(Component.translatable("commands.kuronami_quest_tree.giver.none", entity.getDisplayName()));
                return 0;
            }
            src.sendSuccess(() -> Component.translatable("commands.kuronami_quest_tree.giver.cleared",
                    entity.getDisplayName()), true);
            return 1;
        }
        QuestTaskBlockEntity block = lookedAtBlock(player);
        if (block != null && block.target() != null) {
            block.setTarget(null);
            block.setChanged();
            player.serverLevel().sendBlockUpdated(block.getBlockPos(), block.getBlockState(),
                    block.getBlockState(), 3);
            src.sendSuccess(() -> Component.translatable("commands.kuronami_quest_tree.giver.cleared",
                    Component.literal(block.getBlockPos().toShortString())), true);
            return 1;
        }
        src.sendFailure(Component.translatable("commands.kuronami_quest_tree.giver.none",
                block == null ? Component.literal(player.getName().getString())
                        : Component.literal(block.getBlockPos().toShortString())));
        return 0;
    }

    private static Component playerOnly() {
        return Component.translatable("commands.kuronami_quest_tree.giver.player_only");
    }

    /** The first entity on the player's line of sight, clipped at the first wall like the observation tasks are. */
    private static Entity lookedAtEntity(ServerPlayer player) {
        double reach = player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
        Vec3 eye = player.getEyePosition();
        HitResult wall = player.pick(reach, 1.0F, false);
        if (wall.getType() == HitResult.Type.BLOCK) {
            reach = Math.min(reach, wall.getLocation().distanceTo(eye));
        }
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(reach));
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, end,
                new AABB(eye, end).inflate(1.0),
                e -> e != player && !e.isSpectator() && e.isPickable(), reach * reach);
        return hit == null ? null : hit.getEntity();
    }

    private static QuestTaskBlockEntity lookedAtBlock(ServerPlayer player) {
        double reach = player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
        HitResult hit = player.pick(reach, 1.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult blockHit)) {
            return null;
        }
        return player.serverLevel().getBlockEntity(blockHit.getBlockPos()) instanceof QuestTaskBlockEntity be
                ? be : null;
    }
}
