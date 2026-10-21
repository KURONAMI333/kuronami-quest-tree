package com.kuronami.questtree.mixin;

import com.kuronami.questtree.server.ProgressService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** tame tasks: the animal became tame. */
@Mixin(TamableAnimal.class)
public abstract class TamableAnimalTameMixin {

    @Inject(method = "tame", at = @At("TAIL"))
    private void questTree$tamed(Player player, CallbackInfo ci) {
        TamableAnimal self = (TamableAnimal) (Object) this;
        if (player instanceof ServerPlayer sp && self.isTame()) {
            ProgressService.onTame(sp, self);
        }
    }
}
