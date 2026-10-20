package com.kuronami.questtree.mixin;

import com.kuronami.questtree.server.SkillExperience;
import net.minecraft.advancements.critereon.EnchantedItemTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pufferfish's Skills fires its {@code enchant_item} experience source from the vanilla "enchanted item" criterion,
 * which is triggered with the result stack and the levels spent. Enchanting never went through a craft event, so
 * enchant_item sources gave no experience before 2026-10-20.
 */
@Mixin(EnchantedItemTrigger.class)
public class EnchantedItemCriterionMixin {

    @Inject(method = "trigger", at = @At("HEAD"))
    private void questTree$enchantItem(ServerPlayer player, ItemStack stack, int levels, CallbackInfo ci) {
        if (player != null && !stack.isEmpty()) {
            SkillExperience.onEnchant(player, stack.copy(), levels);
        }
    }
}
