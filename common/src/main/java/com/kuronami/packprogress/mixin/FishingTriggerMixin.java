package com.kuronami.packprogress.mixin;

import com.kuronami.packprogress.server.SkillExperience;
import net.minecraft.advancements.critereon.FishingRodHookedTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

/** fish_item experience: the advancement trigger receives exactly the caught items. */
@Mixin(FishingRodHookedTrigger.class)
public abstract class FishingTriggerMixin {

    @Inject(method = "trigger", at = @At("HEAD"))
    private void packProgress$fished(ServerPlayer player, ItemStack rod, FishingHook hook, Collection<ItemStack> items, CallbackInfo ci) {
        for (ItemStack st : items) {
            SkillExperience.onFish(player, st.copy());
        }
    }
}
