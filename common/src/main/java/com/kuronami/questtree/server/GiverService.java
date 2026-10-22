package com.kuronami.questtree.server;

import com.kuronami.questtree.Constants;
import com.kuronami.questtree.giver.GiverTarget;
import com.kuronami.questtree.network.OpenPayload;
import com.kuronami.questtree.platform.Services;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

/**
 * Opening a quest or chapter from the world (2026-10-22, G3): a linked entity, a quest block, a command or another
 * mod's code. The server sends the target and the client applies its own hidden and locked rules, exactly as it does
 * for a link in a description, so a giver cannot reveal a quest the player may not see.
 * <p>
 * Entity links are keyed by UUID and kept in {@link GiverStore}; a block's link is its block entity's own NBT.
 */
public final class GiverService {

    private GiverService() {
    }

    /**
     * Opens the book on a quest or chapter for this player. Returns false when the id is not in the loaded book, so a
     * caller can tell a bad id from a delivered request.
     */
    public static boolean open(ServerPlayer player, String target) {
        GiverTarget resolved = target == null ? null : GiverTarget.resolve(target, ProgressService.rules().model());
        return resolved != null && send(player, resolved);
    }

    /** Opens an already-resolved link, for a block or an entity whose target is stored. */
    public static boolean openTarget(ServerPlayer player, GiverTarget target) {
        return target != null && send(player, target);
    }

    /** Opens the book where it normally opens (last-viewed chapter); used by a quest block with no link. */
    public static void openDefault(ServerPlayer player) {
        if (player != null) {
            Services.PLATFORM.sendToPlayer(player, new OpenPayload(""));
        }
    }

    /** The link of an entity, or null. */
    public static GiverTarget targetOf(Entity entity) {
        if (entity == null || !(entity.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return null;
        }
        MinecraftServer server = level.getServer();
        return server == null ? null : GiverStore.get(server).target(entity.getUUID());
    }

    public static void link(MinecraftServer server, UUID entity, GiverTarget target) {
        GiverStore.get(server).link(entity, target);
    }

    /** Stores an entity link on the server thread; used by the dev tour and by an addon. */
    public static void link(Entity entity, GiverTarget target) {
        if (entity == null || target == null || !(entity.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (server != null) {
            link(server, entity.getUUID(), target);
        }
    }

    /**
     * A player's right click on an entity. Returns true when a giver answered it and the ordinary interaction (a
     * villager's trade screen, for example) should not run. Sneaking is left alone, so a pack can keep trading on the
     * same villager.
     */
    public static boolean onInteract(ServerPlayer player, Entity entity) {
        if (player == null || player.isSpectator() || player.isShiftKeyDown()) {
            return false;
        }
        GiverTarget target = targetOf(entity);
        if (target == null) {
            return false;
        }
        openTarget(player, target);
        return true;
    }

    private static boolean send(ServerPlayer player, GiverTarget target) {
        if (player == null || player.connection == null) {
            return false;
        }
        Services.PLATFORM.sendToPlayer(player, new OpenPayload(target.encode()));
        Constants.LOG.debug("Opened {} for {}", target.encode(), player.getGameProfile().getName());
        return true;
    }
}
