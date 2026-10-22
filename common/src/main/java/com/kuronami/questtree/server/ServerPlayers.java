package com.kuronami.questtree.server;

import com.kuronami.questtree.progress.FakePlayers;
import net.minecraft.server.level.ServerPlayer;

/** The game-side answer to {@link FakePlayers}: does this {@code ServerPlayer} stand for a person? */
final class ServerPlayers {

    private ServerPlayers() {
    }

    static boolean isFake(ServerPlayer player) {
        return FakePlayers.isFake(player.connection != null, player.getClass() == ServerPlayer.class);
    }
}
