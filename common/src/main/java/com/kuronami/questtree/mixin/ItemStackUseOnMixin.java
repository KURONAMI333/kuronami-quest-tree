package com.kuronami.questtree.mixin;

import com.kuronami.questtree.server.ProgressService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * use_item tasks for items used against a block (hoe, shovel, bucket, ...). The stack is copied before the use, so
 * using the last item still counts; right_click_block is counted by {@link ServerPlayerGameModeUseItemOnMixin}, which
 * also sees blocks the game handles without asking the item (chests, doors).
 */
@Mixin(ItemStack.class)
public abstract class ItemStackUseOnMixin {

    @Unique
    private static final ThreadLocal<ItemStack> QUEST_TREE_USE_ON_STACK = new ThreadLocal<>();

    @Inject(method = "useOn", at = @At("HEAD"))
    private void questTree$beforeUseOn(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (context.getPlayer() instanceof ServerPlayer) {
            QUEST_TREE_USE_ON_STACK.set(context.getItemInHand().copy());
        }
    }

    @Inject(method = "useOn", at = @At("RETURN"))
    private void questTree$afterUseOn(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        ItemStack stack = QUEST_TREE_USE_ON_STACK.get();
        QUEST_TREE_USE_ON_STACK.remove();
        if (stack != null && context.getPlayer() instanceof ServerPlayer sp
                && cir.getReturnValue().consumesAction()) {
            ProgressService.onUseItem(sp, stack);
        }
    }
}
