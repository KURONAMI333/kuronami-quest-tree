package com.kuronami.questtree.mixin;

import com.kuronami.questtree.server.GiverService;
import com.kuronami.questtree.server.GiverStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * An entity that dies or is discarded can no longer open its quest, so its link is removed with it instead of being
 * left in the world save forever (2026-10-22 review, L5). Unloading a chunk and changing dimension keep the link.
 */
@Mixin(Entity.class)
public abstract class EntityRemoveMixin {

    @Inject(method = "remove", at = @At("HEAD"))
    private void questTree$forgetGiver(Entity.RemovalReason reason, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!GiverService.forgetsLink(reason) || !(self.level() instanceof ServerLevel level)) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (server != null) {
            GiverStore.get(server).unlink(self.getUUID());
        }
    }
}
