package com.kuronami.questtree;

import com.kuronami.questtree.server.EditorService;
import com.kuronami.questtree.server.ProgressCommands;
import com.kuronami.questtree.server.ProgressData;
import com.kuronami.questtree.server.PartyActions;
import com.kuronami.questtree.server.ProgressService;
import com.kuronami.questtree.server.SkillExperience;
import com.kuronami.questtree.network.ActionPayload;
import com.kuronami.questtree.progress.ActionRateLimiter;
import com.kuronami.questtree.network.EditorOpPayload;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
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
        Constants.LOG.debug("Kuronami's Quest Tree common init");
    }

    /** Data pack (re)load finished on the server. */
    public static void onDataReload(ResourceManager rm) {
        ProgressData.reload(rm);
    }

    /** A player joined, or data packs were reloaded: send the current bundle. */
    public static void onSyncToPlayer(ServerPlayer player) {
        ProgressData.sync(player);
        EditorService.sendState(player);
        ProgressService.onJoin(player);
        // an operator gets one line when the pack has errors, so a broken pack is not silent; the details need a command
        if (ProgressData.errorCount() > 0 && player.hasPermissions(2)) {
            player.sendSystemMessage(Component.translatable("commands.kuronami_quest_tree.validate.hint", ProgressData.errorCount()));
        }
    }

    /** The server started: the editor overlay lives inside the world that was opened. */
    public static void onServerStarted(MinecraftServer server) {
        EditorService.onServerStarted(server);
    }

    /** The server stopped: forget the world and every editing session. */
    public static void onServerStopped() {
        EditorService.onServerStopped();
    }

    /** End of a server tick: publish at most one editor rebuild for the edits made during the tick. */
    public static void onServerTick(MinecraftServer server) {
        EditorService.onServerTick(server);
    }

    /** Server-side player tick (end of tick). */
    public static void onPlayerTick(ServerPlayer player) {
        ProgressService.onPlayerTick(player);
    }

    /** An edit request from a client in editing mode. */
    public static void onEditorOp(ServerPlayer player, EditorOpPayload op) {
        EditorService.onAction(player, op);
    }

    /** A player killed an entity. */
    public static void onKill(ServerPlayer player, Entity killed) {
        ProgressService.onKill(player, killed);
    }

    /** A player broke a block (survival or creative; the block is already gone). */
    public static void onBlockBroken(ServerPlayer player, BlockState state) {
        SkillExperience.onMine(player, state);
        ProgressService.onBlockBreak(player, state);
    }

    /** A screen action from the client, on the server thread. */
    public static void onAction(ServerPlayer player, ActionPayload action) {
        boolean party = action.action().startsWith("party_");
        // Both paths re-check state and can re-check the whole book, so a client that keeps sending must not be able
        // to spend that work every tick; party invites are the one that fills another player's chat (2026-10-22 review).
        if (!ActionRateLimiter.allow(player.getUUID(), party, System.currentTimeMillis())) {
            return;
        }
        if (party) {
            PartyActions.handle(player, action);
            return;
        }
        ProgressService.onAction(player, action);
    }

    /** A player took damage (after armour and absorption). */
    public static void onTakeDamage(ServerPlayer player, net.minecraft.world.damagesource.DamageSource source, float amount) {
        SkillExperience.onTakeDamage(player, source, amount);
    }

    /** A player damaged another living entity (after the target's armour). */
    public static void onDealDamage(ServerPlayer player, net.minecraft.world.entity.LivingEntity target,
                                    net.minecraft.world.damagesource.DamageSource source, float amount) {
        SkillExperience.onDealDamage(player, target, source, amount);
    }

    /** After respawning (death or returning from the End): attribute modifiers are not copied by vanilla. */
    public static void onDeath(ServerPlayer player) {
        ProgressService.onDeath(player);
    }

    public static void onRespawn(ServerPlayer player, boolean died) {
        ProgressService.onRespawn(player, died);
    }

    public static void onLogout(ServerPlayer player) {
        ProgressService.onLogout(player);
        EditorService.onLogout(player);
        ActionRateLimiter.forget(player.getUUID());
    }

    /** The server is stopping: runtime-only state (anti-farming windows) is dropped. */
    public static void onServerStopping() {
        ProgressService.onServerStopping();
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        ProgressCommands.register(dispatcher);
    }
}
