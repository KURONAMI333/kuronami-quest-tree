package com.kuronami.questtree.mixin;

import com.kuronami.questtree.server.GiverService;
import com.kuronami.questtree.server.ProgressService;
import com.kuronami.questtree.server.Restrictions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * interact_entity tasks: a player right-clicked an entity. A restriction on the held item's {@code use} action also
 * refuses the interaction (DATA_MODEL.md "制限"). An entity linked as a quest giver opens the book instead of its own
 * interaction (2026-10-22, G3); sneaking still reaches the entity's normal interaction.
 */
@Mixin(Player.class)
public abstract class PlayerInteractEntityMixin {

    @Inject(method = "interactOn", at = @At("HEAD"), cancellable = true)
    private void questTree$beforeInteractOn(Entity entity, InteractionHand hand,
                                            CallbackInfoReturnable<InteractionResult> cir) {
        if ((Object) this instanceof ServerPlayer sp
                && Restrictions.blockedItem(sp, sp.getItemInHand(hand), "use")) {
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }
        if ((Object) this instanceof ServerPlayer sp && GiverService.onInteract(sp, entity)) {
            cir.setReturnValue(InteractionResult.SUCCESS);
        }
    }

    @Inject(method = "interactOn", at = @At("RETURN"))
    private void questTree$interacted(Entity entity, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if ((Object) this instanceof ServerPlayer sp && cir.getReturnValue().consumesAction()) {
            ProgressService.onInteractEntity(sp, entity);
        }
    }
}
