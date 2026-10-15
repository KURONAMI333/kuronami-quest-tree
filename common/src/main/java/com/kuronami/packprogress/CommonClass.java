package com.kuronami.packprogress;

import com.kuronami.packprogress.server.ProgressCommands;
import com.kuronami.packprogress.server.ProgressData;
import com.kuronami.packprogress.server.ProgressService;
import com.kuronami.packprogress.server.SkillExperience;
import com.kuronami.packprogress.network.ActionPayload;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
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
        ProgressService.onJoin(player);
    }

    /** Server-side player tick (end of tick). */
    public static void onPlayerTick(ServerPlayer player) {
        ProgressService.onPlayerTick(player);
    }

    /** A player killed an entity. */
    public static void onKill(ServerPlayer player, Entity killed) {
        ProgressService.onKill(player, killed);
    }

    /** A player broke a block (survival or creative; the block is already gone). */
    public static void onBlockBroken(ServerPlayer player, BlockState state) {
        SkillExperience.onMine(player, state);
    }

    /** A screen action from the client, on the server thread. */
    public static void onAction(ServerPlayer player, ActionPayload action) {
        ProgressService.onAction(player, action);
    }

    public static void onLogout(ServerPlayer player) {
        ProgressService.onLogout(player);
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        ProgressCommands.register(dispatcher);
    }
}
