package com.kuronami.questtree.mixin;

import com.kuronami.questtree.server.Restrictions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A restriction on the held item's {@code attack} action refuses the hit before damage is dealt. */
@Mixin(Player.class)
public abstract class PlayerAttackMixin {

    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void questTree$beforeAttack(Entity target, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer sp
                && Restrictions.blockedItem(sp, sp.getMainHandItem(), "attack")) {
            ci.cancel();
        }
    }
}
