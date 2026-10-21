package com.kuronami.questtree.mixin;

import com.kuronami.questtree.server.ProgressService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.animal.Animal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * breed tasks: credit the player vanilla recorded as the love cause, once the child was actually created. The old
 * feeder map went stale and credited the wrong player after an animal was fed again (2026-10-21 review).
 */
@Mixin(Animal.class)
public abstract class AnimalBreedingMixin {

    @Inject(method = "finalizeSpawnChildFromBreeding", at = @At("HEAD"))
    private void questTree$bred(ServerLevel level, Animal partner, AgeableMob child, CallbackInfo ci) {
        ProgressService.onBreed(level, (Animal) (Object) this, partner);
    }
}
