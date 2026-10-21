package com.kuronami.questtree.mixin;

import com.kuronami.questtree.server.ProgressService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** right_click_block tasks: an item was used against a block (placed, opened, tilled, ...). */
@Mixin(ItemStack.class)
public abstract class ItemStackUseOnMixin {

    @Inject(method = "useOn", at = @At("RETURN"))
    private void questTree$usedOn(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (context.getPlayer() instanceof ServerPlayer sp && cir.getReturnValue().consumesAction()) {
            ProgressService.onRightClickBlock(sp, context.getLevel().getBlockState(context.getClickedPos()));
        }
    }
}
