package com.kuronami.questtree.mixin;

import com.kuronami.questtree.server.ProgressService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** use_item tasks: an item was used (eaten, drunk, thrown, drawn, placed by use). */
@Mixin(ItemStack.class)
public abstract class ItemStackUseMixin {

    @Inject(method = "use", at = @At("RETURN"))
    private void questTree$used(Level level, Player player, InteractionHand hand,
                                CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir) {
        if (player instanceof ServerPlayer sp && cir.getReturnValue().getResult().consumesAction()) {
            ProgressService.onUseItem(sp, (ItemStack) (Object) this);
        }
    }
}
