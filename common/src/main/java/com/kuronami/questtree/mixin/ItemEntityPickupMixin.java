package com.kuronami.questtree.mixin;

import com.kuronami.questtree.server.ProgressService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** pickup tasks: the item was actually taken (the entity is removed after a successful pickup). */
@Mixin(ItemEntity.class)
public abstract class ItemEntityPickupMixin {

    @Inject(method = "playerTouch", at = @At("TAIL"))
    private void questTree$pickedUp(Player player, CallbackInfo ci) {
        ItemEntity self = (ItemEntity) (Object) this;
        if (!self.level().isClientSide && player instanceof ServerPlayer sp && self.isRemoved()
                && com.kuronami.questtree.progress.PickupRule.counts(self.getOwner() == null ? null : self.getOwner().getUUID(),
                sp.getUUID())) {
            ProgressService.onPickup(sp, self.getItem().copy());
        }
    }
}
