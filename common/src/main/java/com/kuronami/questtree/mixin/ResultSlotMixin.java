package com.kuronami.questtree.mixin;

import com.kuronami.questtree.server.ProgressService;
import com.kuronami.questtree.server.SkillExperience;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
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
    @Final
    private CraftingContainer craftSlots;

    @Shadow
    private int removeCount;

    @Inject(method = "checkTakeAchievements", at = @At("HEAD"))
    private void questTree$crafted(ItemStack stack, CallbackInfo ci) {
        // vanilla skips the second call of a partial shift-click (removeCount already 0); so do we, or it counts twice
        if (removeCount > 0 && player instanceof ServerPlayer sp && !stack.isEmpty()) {
            SkillExperience.onCraft(sp, stack.copy());
            String recipe = "";
            try {
                recipe = sp.server.getRecipeManager()
                        .getRecipeFor(RecipeType.CRAFTING, craftSlots.asCraftInput(), sp.level())
                        .map(holder -> holder.id().toString()).orElse("");
            } catch (RuntimeException ignored) {
                // a custom menu around ResultSlot may not expose a crafting recipe; item matching still works
            }
            ProgressService.onCraft(sp, stack.copy(), recipe);
        }
    }
}
