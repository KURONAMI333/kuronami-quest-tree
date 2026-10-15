package com.kuronami.questtree.mixin;

import com.kuronami.questtree.FabricDamage;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Player overrides actuallyHurt without calling super, so players need their own hook (see FabricActuallyHurtMixin). */
@Mixin(Player.class)
public abstract class FabricPlayerActuallyHurtMixin {

    @Unique
    private float questTree$healthBefore;

    @Inject(method = "actuallyHurt", at = @At("HEAD"))
    private void questTree$before(DamageSource source, float amount, CallbackInfo ci) {
        questTree$healthBefore = ((Player) (Object) this).getHealth();
    }

    @Inject(method = "actuallyHurt", at = @At("RETURN"))
    private void questTree$after(DamageSource source, float amount, CallbackInfo ci) {
        FabricDamage.report((Player) (Object) this, source, questTree$healthBefore);
    }
}
