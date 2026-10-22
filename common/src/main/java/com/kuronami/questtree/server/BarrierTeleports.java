package com.kuronami.questtree.server;

import com.kuronami.questtree.block.BarrierTeleport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Next-tick barrier teleports. Moving a player during the collision check that discovered them is unsafe, so the gate
 * only queues the destination; the end-of-tick hook moves them once, like FTB Quests' barrier.
 */
public final class BarrierTeleports {

    private record Pending(ResourceKey<Level> dimension, double x, double y, double z, Float yaw, Float pitch) {
    }

    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    private BarrierTeleports() {
    }

    public static void queue(ServerPlayer player, BarrierTeleport teleport, BlockPos barrier) {
        ResourceLocation id = teleport.dimension().isEmpty() ? null : ResourceLocation.tryParse(teleport.dimension());
        ResourceKey<Level> dimension = id == null ? null : ResourceKey.create(Registries.DIMENSION, id);
        PENDING.put(player.getUUID(), new Pending(dimension,
                teleport.destX(barrier.getX()), teleport.destY(barrier.getY()), teleport.destZ(barrier.getZ()),
                teleport.yaw(), teleport.pitch()));
    }

    /** Runs at the end of every server tick. */
    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        Map<UUID, Pending> teleports = Map.copyOf(PENDING);
        PENDING.clear();
        for (Map.Entry<UUID, Pending> entry : teleports.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }
            Pending target = entry.getValue();
            ServerLevel level = target.dimension() == null ? null : server.getLevel(target.dimension());
            if (level == null && player.level() instanceof ServerLevel current) {
                level = current;
            }
            if (level == null) {
                continue;
            }
            float yaw = target.yaw() != null ? target.yaw() : player.getYRot();
            float pitch = target.pitch() != null ? target.pitch() : player.getXRot();
            player.teleportTo(level, target.x(), target.y(), target.z(), yaw, pitch);
            level.playSound(null, target.x(), target.y(), target.z(),
                    SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.5F, 1.0F);
        }
    }

    public static void clear() {
        PENDING.clear();
    }
}
