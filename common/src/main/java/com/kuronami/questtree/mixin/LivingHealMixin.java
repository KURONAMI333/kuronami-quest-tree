package com.kuronami.questtree.mixin;

import com.kuronami.questtree.server.SkillExperience;
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
    private float questTree$before;
    /** A heal listener may heal again inside heal(): only the outermost call is measured, so nothing counts twice. */
    @Unique
    private int questTree$depth;

    @Inject(method = "heal", at = @At("HEAD"))
    private void questTree$beforeHeal(float amount, CallbackInfo ci) {
        if (questTree$depth++ == 0) {
            questTree$before = ((LivingEntity) (Object) this).getHealth();
        }
    }

    @Inject(method = "heal", at = @At("RETURN"))
    private void questTree$afterHeal(float amount, CallbackInfo ci) {
        // counting down in finally keeps a throw from our own report (or an addon listener it calls) from leaving the
        // counter stuck, which would silence every later heal for this entity (2026-10-22 release review)
        try {
            if (questTree$depth == 1 && (Object) this instanceof ServerPlayer sp) {
                float gained = sp.getHealth() - questTree$before;
                if (gained > 0) {
                    SkillExperience.onHeal(sp, gained);
                }
            }
        } finally {
            if (questTree$depth > 0) {
                questTree$depth--;
            }
        }
    }
}
