package com.kuronami.questtree.mixin;

import com.kuronami.questtree.server.Restrictions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * craft restrictions (both loaders): the pickup and the shift-click paths both ask {@link Slot#mayPickup} before they
 * move anything, so refusing it here leaves the result in the slot and the ingredients untouched.
 */
@Mixin(Slot.class)
public abstract class SlotMixin {

    @Inject(method = "mayPickup", at = @At("HEAD"), cancellable = true)
    private void questTree$blockedCraft(Player player, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ResultSlot && player instanceof ServerPlayer sp) {
            ItemStack result = ((Slot) (Object) this).getItem();
            if (Restrictions.blockedCraft(sp, result)) {
                cir.setReturnValue(false);
            }
        }
    }
}
