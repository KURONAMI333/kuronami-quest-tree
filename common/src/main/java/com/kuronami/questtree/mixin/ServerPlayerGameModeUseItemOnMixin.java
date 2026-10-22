package com.kuronami.questtree.mixin;

import com.kuronami.questtree.server.ProgressService;
import com.kuronami.questtree.server.Restrictions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * right_click_block tasks for every block the player successfully uses. The game handles chests, doors and other
 * blocks itself without calling {@code ItemStack.useOn}, so hooking the item alone missed them; the state is read
 * before the interaction, because a door or button changes it (2026-10-21 review).
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeUseItemOnMixin {

    @Unique
    private static final ThreadLocal<BlockState> QUEST_TREE_CLICKED_STATE = new ThreadLocal<>();

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void questTree$beforeUseItemOn(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
                                          BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        BlockState state = level.getBlockState(hit.getBlockPos());
        // the item's and the block's use restrictions both refuse the interaction (DATA_MODEL.md "制限")
        if (Restrictions.blockedItem(player, stack, "use") || Restrictions.blockedBlock(player, state, "use")) {
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }
        QUEST_TREE_CLICKED_STATE.set(state);
    }

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void questTree$afterUseItemOn(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
                                         BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        BlockState state = QUEST_TREE_CLICKED_STATE.get();
        QUEST_TREE_CLICKED_STATE.remove();
        // a spectator does not really use a block, so their clicks must not count for right_click_block tasks
        if (state != null && !player.isSpectator() && cir.getReturnValue().consumesAction()) {
            ProgressService.onRightClickBlock(player, state);
        }
    }
}
