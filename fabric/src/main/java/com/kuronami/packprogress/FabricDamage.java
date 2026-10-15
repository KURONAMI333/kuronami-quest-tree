package com.kuronami.packprogress;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/** Damage experience on Fabric, measured as health actually lost (the same numbers NeoForge's LivingDamageEvent.Post gives). */
public final class FabricDamage {

    private FabricDamage() {
    }

    /** Reports health actually lost by a hit (called from the actuallyHurt mixins of LivingEntity and Player). */
    public static void report(LivingEntity self, DamageSource source, float healthBefore) {
        if (self.level().isClientSide()) {
            return;
        }
        float lost = healthBefore - self.getHealth();
        if (lost <= 0) {
            return;
        }
        if (self instanceof ServerPlayer sp) {
            CommonClass.onTakeDamage(sp, source, lost);
        }
        if (source.getEntity() instanceof ServerPlayer sp && self != sp) {
            CommonClass.onDealDamage(sp, self, source, lost);
        }
    }
}
