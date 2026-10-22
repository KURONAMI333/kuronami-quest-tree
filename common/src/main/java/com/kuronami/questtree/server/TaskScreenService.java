package com.kuronami.questtree.server;

import com.google.gson.JsonObject;
import com.kuronami.questtree.block.TaskScreenAuxBlockEntity;
import com.kuronami.questtree.block.TaskScreenBlockEntity;
import com.kuronami.questtree.data.ProgressModel;
import com.kuronami.questtree.network.ScreenBindOpenPayload;
import com.kuronami.questtree.platform.Services;
import com.kuronami.questtree.progress.PlayerProgress;
import com.kuronami.questtree.progress.ProgressRules;
import com.kuronami.questtree.progress.ScreenRules;
import com.kuronami.questtree.team.TeamResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;

import java.util.UUID;

/**
 * What the task screens do on the server (2026-10-22, FTB Quests parity): open the picker for someone allowed to
 * edit the screen, bind a task, and turn inserted items, fluid and FE into progress for the screen's owner.
 * <p>
 * A screen stores the placing player, not the team: {@link ProgressStore#view} resolves the owner's current team, so
 * a screen keeps working after the owner joins or leaves a party.
 */
public final class TaskScreenService {

    private TaskScreenService() {
    }

    /** The core block entity behind a core or aux position, or null. */
    public static TaskScreenBlockEntity coreAt(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof TaskScreenBlockEntity core) {
            return core;
        }
        if (level.getBlockEntity(pos) instanceof TaskScreenAuxBlockEntity aux) {
            return aux.core().orElse(null);
        }
        return null;
    }

    /** FTB Quests lets the owner and their team members edit a screen; everyone else may not even open the picker. */
    public static boolean canEdit(MinecraftServer server, UUID owner, ServerPlayer player) {
        if (owner == null || player == null) {
            return false;
        }
        if (owner.equals(player.getUUID())) {
            return true;
        }
        return TeamResolver.teamId(server, owner).equals(TeamResolver.teamId(server, player.getUUID()));
    }

    public static void requestOpen(ServerPlayer player, BlockPos pos) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        TaskScreenBlockEntity core = coreAt(level, pos);
        if (core == null) {
            return;
        }
        if (!canEdit(level.getServer(), core.owner(), player)) {
            Cues.notice(player, "screen.kuronami_quest_tree.task_screen.no_permission", "minecraft:barrier");
            return;
        }
        Services.PLATFORM.sendToPlayer(player, new ScreenBindOpenPayload(core.getBlockPos()));
    }

    public static void bind(ServerPlayer player, BlockPos pos, String node, int index) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        TaskScreenBlockEntity core = coreAt(level, pos);
        if (core == null) {
            return;
        }
        if (!canEdit(level.getServer(), core.owner(), player)) {
            Cues.notice(player, "screen.kuronami_quest_tree.task_screen.no_permission", "minecraft:barrier");
            return;
        }
        String key = node == null ? "" : node;
        if (key.isEmpty()) {
            core.clearBinding();
            level.playSound(null, core.getBlockPos(), SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 0.6F, 1F);
            return;
        }
        ProgressModel.Node n = ProgressService.rules().model().nodes.get(key);
        if (n == null || index < 0 || index >= n.tasks.size() || !ScreenRules.screenCapable(n.tasks.get(index))) {
            Cues.notice(player, "screen.kuronami_quest_tree.task_screen.not_supported", "minecraft:barrier");
            return;
        }
        core.setBinding(key, index);
        level.playSound(null, core.getBlockPos(), SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.6F, 1F);
    }

    /** Inserts up to {@code stack.getCount()} matching items; returns how many were taken. */
    public static long insertItem(ServerLevel level, TaskScreenBlockEntity core, ItemStack stack) {
        if (stack == null || stack.isEmpty() || core.owner() == null) {
            return 0;
        }
        ProgressModel.Node n = taskNode(core);
        if (n == null) {
            return 0;
        }
        JsonObject task = n.tasks.get(core.taskIndex());
        if (!canSubmit(level.getServer(), core, n)
                || !ScreenRules.takesItem(n, task) || !ScreenRules.acceptsItem(task, stack, level.registryAccess())) {
            return 0;
        }
        long remaining = remaining(level.getServer(), core, n, task);
        long accepted = Math.min(stack.getCount(), remaining);
        if (accepted <= 0) {
            return 0;
        }
        write(level.getServer(), core, n, task, accepted);
        return accepted;
    }

    public static long insertFluid(ServerLevel level, TaskScreenBlockEntity core, Fluid fluid, long amount) {
        if (fluid == null || amount <= 0 || core.owner() == null) {
            return 0;
        }
        ProgressModel.Node n = taskNode(core);
        if (n == null) {
            return 0;
        }
        JsonObject task = n.tasks.get(core.taskIndex());
        if (!canSubmit(level.getServer(), core, n) || !ScreenRules.acceptsFluid(task, fluid)) {
            return 0;
        }
        long accepted = Math.min(amount, remaining(level.getServer(), core, n, task));
        if (accepted <= 0) {
            return 0;
        }
        write(level.getServer(), core, n, task, accepted);
        return accepted;
    }

    public static long insertEnergy(ServerLevel level, TaskScreenBlockEntity core, long amount) {
        if (amount <= 0 || core.owner() == null) {
            return 0;
        }
        ProgressModel.Node n = taskNode(core);
        if (n == null) {
            return 0;
        }
        JsonObject task = n.tasks.get(core.taskIndex());
        if (!canSubmit(level.getServer(), core, n) || !ScreenRules.acceptsEnergy(task)) {
            return 0;
        }
        long limit = ScreenRules.maxInput(task);
        if (limit > 0) {
            amount = Math.min(amount, limit);
        }
        long accepted = Math.min(amount, remaining(level.getServer(), core, n, task));
        if (accepted <= 0) {
            return 0;
        }
        write(level.getServer(), core, n, task, accepted);
        return accepted;
    }

    /** How many more of this stack the screen takes (0 for a task that does not match). */
    public static long itemCapacity(ServerLevel level, TaskScreenBlockEntity core, ItemStack stack) {
        if (stack == null || stack.isEmpty() || core.owner() == null) {
            return 0;
        }
        ProgressModel.Node n = taskNode(core);
        if (n == null) {
            return 0;
        }
        JsonObject task = n.tasks.get(core.taskIndex());
        if (!canSubmit(level.getServer(), core, n)
                || !ScreenRules.takesItem(n, task) || !ScreenRules.acceptsItem(task, stack, level.registryAccess())) {
            return 0;
        }
        return remaining(level.getServer(), core, n, task);
    }

    public static long fluidCapacity(ServerLevel level, TaskScreenBlockEntity core, Fluid fluid) {
        if (fluid == null || core.owner() == null) {
            return 0;
        }
        ProgressModel.Node n = taskNode(core);
        if (n == null) {
            return 0;
        }
        JsonObject task = n.tasks.get(core.taskIndex());
        if (!canSubmit(level.getServer(), core, n) || !ScreenRules.acceptsFluid(task, fluid)) {
            return 0;
        }
        return remaining(level.getServer(), core, n, task);
    }

    public static long energyCapacity(ServerLevel level, TaskScreenBlockEntity core) {
        if (core.owner() == null) {
            return 0;
        }
        ProgressModel.Node n = taskNode(core);
        if (n == null) {
            return 0;
        }
        JsonObject task = n.tasks.get(core.taskIndex());
        if (!canSubmit(level.getServer(), core, n) || !ScreenRules.acceptsEnergy(task)) {
            return 0;
        }
        return remaining(level.getServer(), core, n, task);
    }

    /** The progress a screen's owner (through their team) currently has for the bound task. */
    public static long progress(MinecraftServer server, UUID owner, String node, int index) {
        if (server == null || owner == null || node == null || node.isEmpty()) {
            return 0;
        }
        ProgressModel.Node n = ProgressService.rules().model().nodes.get(node);
        if (n == null || index < 0 || index >= n.tasks.size()) {
            return 0;
        }
        PlayerProgress p = ProgressStore.get(server).view(server, owner);
        return Math.min(p.task(node, index), ProgressRules.target(n.tasks.get(index)));
    }

    private static ProgressModel.Node taskNode(TaskScreenBlockEntity core) {
        if (core.node().isEmpty()) {
            return null;
        }
        ProgressModel.Node n = ProgressService.rules().model().nodes.get(core.node());
        return n == null || core.taskIndex() < 0 || core.taskIndex() >= n.tasks.size() ? null : n;
    }

    /** FTB Quests only feeds a screen while its quest can start and its task is not complete. */
    private static boolean canSubmit(MinecraftServer server, TaskScreenBlockEntity core, ProgressModel.Node n) {
        ProgressRules rules = ProgressService.rules();
        if (server == null || rules == null) {
            return false;
        }
        PlayerProgress owner = ProgressStore.get(server).view(server, core.owner());
        if (owner.bookLocked()) {
            return false;
        }
        ProgressRules.State state = rules.state(owner, n);
        return state == ProgressRules.State.OPEN || state == ProgressRules.State.PROGRESS;
    }

    private static long remaining(MinecraftServer server, TaskScreenBlockEntity core, ProgressModel.Node n, JsonObject task) {
        long target = ProgressRules.target(task);
        PlayerProgress p = ProgressStore.get(server).view(server, core.owner());
        return Math.max(0, target - p.task(n.key, core.taskIndex()));
    }

    private static void write(MinecraftServer server, TaskScreenBlockEntity core, ProgressModel.Node n,
                              JsonObject task, long amount) {
        PlayerProgress p = ProgressStore.get(server).view(server, core.owner());
        long before = p.task(n.key, core.taskIndex());
        long next = Math.min(ProgressRules.target(task), before + amount);
        p.setTask(n.key, core.taskIndex(), next);
        ProgressService.screenChanged(server, core.owner());
        core.refreshProgress();
    }
}
