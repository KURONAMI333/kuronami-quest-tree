package com.kuronami.questtree.mixin;

import com.kuronami.questtree.server.ProgressService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** interact_entity tasks: a player right-clicked an entity. */
@Mixin(Player.class)
public abstract class PlayerInteractEntityMixin {

    @Inject(method = "interactOn", at = @At("RETURN"))
    private void questTree$interacted(Entity entity, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if ((Object) this instanceof ServerPlayer sp && cir.getReturnValue().consumesAction()) {
            ProgressService.onInteractEntity(sp, entity);
        }
    }
}
