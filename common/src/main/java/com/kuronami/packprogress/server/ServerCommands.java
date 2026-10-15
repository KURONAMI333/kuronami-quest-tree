package com.kuronami.packprogress.server;

import com.kuronami.packprogress.Constants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/** Runs a command as the player (position, @s) with a given permission level. */
final class ServerCommands {

    private ServerCommands() {
    }

    /** Returns the command's result (0 when it failed or was rejected). */
    static int run(ServerPlayer player, String command, int permission, boolean silent) {
        CommandSourceStack src = player.createCommandSourceStack().withPermission(Math.max(0, Math.min(4, permission)));
        if (silent) {
            src = src.withSuppressedOutput();
        }
        int[] result = {0};
        src = src.withCallback((ok, value) -> result[0] = ok ? value : 0);
        try {
            player.server.getCommands().performPrefixedCommand(src, command);
        } catch (RuntimeException ex) {
            Constants.LOG.warn("Command '{}' failed: {}", command, ex.toString());
            return 0;
        }
        return result[0];
    }
}
