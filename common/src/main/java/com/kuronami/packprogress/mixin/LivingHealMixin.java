package com.kuronami.packprogress.mixin;

import com.kuronami.packprogress.server.SkillExperience;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** heal experience: the health actually gained (nothing when already full or when another mod cancels the heal). */
@Mixin(LivingEntity.class)
public abstract class LivingHealMixin {

    @Unique
    private float packProgress$before;

    @Inject(method = "heal", at = @At("HEAD"))
    private void packProgress$beforeHeal(float amount, CallbackInfo ci) {
        packProgress$before = ((LivingEntity) (Object) this).getHealth();
    }

    @Inject(method = "heal", at = @At("RETURN"))
    private void packProgress$afterHeal(float amount, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer sp) {
            float gained = sp.getHealth() - packProgress$before;
            if (gained > 0) {
                SkillExperience.onHeal(sp, gained);
            }
        }
    }
}
