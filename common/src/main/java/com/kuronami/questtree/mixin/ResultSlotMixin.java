package com.kuronami.questtree.mixin;

import com.kuronami.questtree.server.ProgressService;
import com.kuronami.questtree.server.SkillExperience;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** craft_item experience (both loaders, so Fabric and NeoForge count crafting the same way). */
@Mixin(ResultSlot.class)
public abstract class ResultSlotMixin {

    @Shadow
    @Final
    private Player player;

    @Shadow
    private int removeCount;

    @Inject(method = "checkTakeAchievements", at = @At("HEAD"))
    private void questTree$crafted(ItemStack stack, CallbackInfo ci) {
        // vanilla skips the second call of a partial shift-click (removeCount already 0); so do we, or it counts twice
        if (removeCount > 0 && player instanceof ServerPlayer sp && !stack.isEmpty()) {
            SkillExperience.onCraft(sp, stack.copy());
            ProgressService.onCraft(sp, stack.copy());
        }
    }
}
