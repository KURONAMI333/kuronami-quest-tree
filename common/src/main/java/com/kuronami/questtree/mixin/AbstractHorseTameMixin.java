package com.kuronami.questtree.mixin;

import com.kuronami.questtree.server.ProgressService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** tame tasks for horses, donkeys and mules, which are not {@code TamableAnimal} (2026-10-21 review). */
@Mixin(AbstractHorse.class)
public abstract class AbstractHorseTameMixin {

    @Inject(method = "tameWithName", at = @At("RETURN"))
    private void questTree$tamedHorse(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue()) && player instanceof ServerPlayer sp) {
            ProgressService.onTame(sp, (AbstractHorse) (Object) this);
        }
    }
}
