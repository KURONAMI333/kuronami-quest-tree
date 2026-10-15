package com.kuronami.questtree.mixin;

import com.kuronami.questtree.CommonClass;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fabric's damage events report the damage before armour and fire neither for killing blows nor for the part of a hit
 * absorbed by invulnerability frames. NeoForge reports the damage after armour (LivingDamageEvent.Post). To give the
 * same experience on both loaders, Fabric measures the health actually lost inside actuallyHurt.
 */
@Mixin(LivingEntity.class)
public abstract class FabricActuallyHurtMixin {

    @Unique
    private float questTree$healthBefore;

    @Inject(method = "actuallyHurt", at = @At("HEAD"))
    private void questTree$before(DamageSource source, float amount, CallbackInfo ci) {
        questTree$healthBefore = ((LivingEntity) (Object) this).getHealth();
    }

    @Inject(method = "actuallyHurt", at = @At("RETURN"))
    private void questTree$after(DamageSource source, float amount, CallbackInfo ci) {
        com.kuronami.questtree.FabricDamage.report((LivingEntity) (Object) this, source, questTree$healthBefore);
    }
}
