package com.kuronami.packprogress;

import com.kuronami.packprogress.server.ProgressCommands;
import com.kuronami.packprogress.server.ProgressData;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Loader-independent entry points. Each loader module forwards its events here so the behaviour stays identical on
 * NeoForge and Fabric.
 */
public final class CommonClass {

    private CommonClass() {
    }

    public static void init() {
        Constants.LOG.debug("Pack Progress common init");
    }

    /** Data pack (re)load finished on the server. */
    public static void onDataReload(ResourceManager rm) {
        ProgressData.reload(rm);
    }

    /** A player joined, or data packs were reloaded: send the current bundle. */
    public static void onSyncToPlayer(ServerPlayer player) {
        ProgressData.sync(player);
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        ProgressCommands.register(dispatcher);
    }
}
