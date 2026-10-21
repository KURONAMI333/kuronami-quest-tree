package com.kuronami.questtree.mixin;

import com.kuronami.questtree.server.ProgressService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** ride tasks: a player mounted an entity. */
@Mixin(Entity.class)
public abstract class EntityStartRidingMixin {

    @Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;Z)Z", at = @At("RETURN"))
    private void questTree$startedRiding(Entity vehicle, boolean force, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerPlayer sp && cir.getReturnValueZ()) {
            ProgressService.onRide(sp, vehicle);
        }
    }
}
