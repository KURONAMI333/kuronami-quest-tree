package com.kuronami.packprogress.mixin;

import com.kuronami.packprogress.server.SkillExperience;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** eat_food experience. */
@Mixin(Player.class)
public abstract class PlayerEatMixin {

    @Inject(method = "eat", at = @At("HEAD"))
    private void packProgress$eat(Level level, ItemStack food, FoodProperties props, CallbackInfoReturnable<ItemStack> cir) {
        if ((Object) this instanceof ServerPlayer sp) {
            SkillExperience.onEat(sp, food.copy());
        }
    }
}
