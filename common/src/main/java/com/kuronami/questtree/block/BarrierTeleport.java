package com.kuronami.questtree.block;

/**
 * Where a quest barrier sends a player who passes it. Relative destinations are measured from the barrier block, as in
 * FTB Quests. A null yaw or pitch keeps the player's current facing. Kept free of game classes so the relative
 * arithmetic can be tested without Minecraft.
 */
public record BarrierTeleport(boolean relative, String dimension, int x, int y, int z, Float yaw, Float pitch) {

    public BarrierTeleport {
        dimension = dimension == null ? "" : dimension;
    }

    public static BarrierTeleport absolute(String dimension, int x, int y, int z, Float yaw, Float pitch) {
        return new BarrierTeleport(false, dimension, x, y, z, yaw, pitch);
    }

    public static BarrierTeleport relative(String dimension, int x, int y, int z, Float yaw, Float pitch) {
        return new BarrierTeleport(true, dimension, x, y, z, yaw, pitch);
    }

    /** The X coordinate to teleport to when the barrier is at {@code barrierX}. */
    public double destX(int barrierX) {
        return (relative ? barrierX : 0) + x + 0.5D;
    }

    /** The Y coordinate to teleport to when the barrier is at {@code barrierY}. */
    public double destY(int barrierY) {
        return (relative ? barrierY : 0) + y;
    }

    /** The Z coordinate to teleport to when the barrier is at {@code barrierZ}. */
    public double destZ(int barrierZ) {
        return (relative ? barrierZ : 0) + z + 0.5D;
    }
}
