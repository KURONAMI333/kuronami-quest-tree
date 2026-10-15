package com.kuronami.questtree.mixin;

import com.kuronami.questtree.server.SkillExperience;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.FurnaceResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** smelt_item experience: taking the result out of a furnace, smoker or blast furnace. */
@Mixin(FurnaceResultSlot.class)
public abstract class FurnaceResultSlotMixin {

    @Shadow
    @Final
    private Player player;

    @Shadow
    private int removeCount;

    @Inject(method = "checkTakeAchievements", at = @At("HEAD"))
    private void questTree$smelted(ItemStack stack, CallbackInfo ci) {
        // vanilla skips the second call of a partial shift-click (removeCount already 0); so do we, or it counts twice
        if (removeCount > 0 && player instanceof ServerPlayer sp && !stack.isEmpty()) {
            SkillExperience.onSmelt(sp, stack.copy());
        }
    }
}
