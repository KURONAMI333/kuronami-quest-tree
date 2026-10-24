package com.kuronami.questtree.mixin;

import com.kuronami.questtree.server.ProgressService;
import com.kuronami.questtree.server.Restrictions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A restriction on the {@code place} action refuses the block before it appears. BlockItem is hooked rather than the
 * player's use path because a placement may come from a block that is not a plain right-click.
 */
@Mixin(BlockItem.class)
public abstract class BlockItemPlaceMixin {

    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void questTree$beforePlace(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (context.getPlayer() instanceof ServerPlayer sp
                && Restrictions.blockedPlacement(sp, context.getItemInHand())) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }

    @Inject(method = "place", at = @At("RETURN"))
    private void questTree$afterPlace(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        // only the player's own successful BlockItem placement counts; dispensers and pistons never come through here
        if (context.getPlayer() instanceof ServerPlayer sp && !sp.isSpectator()
                && cir.getReturnValue().consumesAction()) {
            ProgressService.onBlockPlace(sp, context.getLevel().getBlockState(context.getClickedPos()));
        }
    }
}
