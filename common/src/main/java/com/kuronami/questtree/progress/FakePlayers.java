package com.kuronami.questtree.progress;

/**
 * Which {@code ServerPlayer}s are not people: a machine mod's fake player (Create's deployer, a tunnel, a drill) has
 * no connection, and a mod's own subclass of {@code ServerPlayer} is a stand-in by definition. Such a player is never
 * restricted and gets no progress record (DATA_MODEL.md "Restrictions", 2026-10-22 review, L3). Plain booleans only, so
 * a test needs no game.
 */
public final class FakePlayers {

    private FakePlayers() {
    }

    public static boolean isFake(boolean hasConnection, boolean exactServerPlayerClass) {
        return !hasConnection || !exactServerPlayerClass;
    }
}
