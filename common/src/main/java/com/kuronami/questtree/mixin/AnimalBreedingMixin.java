package com.kuronami.questtree.mixin;

import com.kuronami.questtree.server.ProgressService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** breed tasks: remember the feeder, then credit them when the child is born. */
@Mixin(Animal.class)
public abstract class AnimalBreedingMixin {

    @Inject(method = "setInLove", at = @At("TAIL"))
    private void questTree$rememberBreeder(Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer sp) {
            ProgressService.rememberBreeder((Animal) (Object) this, sp);
        }
    }

    @Inject(method = "spawnChildFromBreeding", at = @At("HEAD"))
    private void questTree$bred(ServerLevel level, Animal partner, CallbackInfo ci) {
        ProgressService.onBreed(level, (Animal) (Object) this, partner);
    }
}
