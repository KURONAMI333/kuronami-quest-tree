package com.kuronami.packprogress.mixin;

import com.kuronami.packprogress.FabricDamage;
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
    private float packProgress$healthBefore;

    @Inject(method = "actuallyHurt", at = @At("HEAD"))
    private void packProgress$before(DamageSource source, float amount, CallbackInfo ci) {
        packProgress$healthBefore = ((Player) (Object) this).getHealth();
    }

    @Inject(method = "actuallyHurt", at = @At("RETURN"))
    private void packProgress$after(DamageSource source, float amount, CallbackInfo ci) {
        FabricDamage.report((Player) (Object) this, source, packProgress$healthBefore);
    }
}
