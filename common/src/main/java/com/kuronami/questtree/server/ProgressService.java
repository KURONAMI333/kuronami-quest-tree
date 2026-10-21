package com.kuronami.questtree.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kuronami.questtree.Constants;
import com.kuronami.questtree.config.ServerConfig;
import com.kuronami.questtree.data.ProgressModel;
import com.kuronami.questtree.item.ModItems;
import com.kuronami.questtree.network.ActionPayload;
import com.kuronami.questtree.network.ProgressPayload;
import com.kuronami.questtree.platform.Services;
import com.kuronami.questtree.progress.ItemRequirement;
import com.kuronami.questtree.progress.PlayerProgress;
import com.kuronami.questtree.progress.ProgressJson;
import com.kuronami.questtree.progress.ProgressRules;
import com.kuronami.questtree.progress.ProgressRules.State;
import com.kuronami.questtree.progress.TeamProgress;
import com.kuronami.questtree.team.TeamResolver;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.TagParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server side of progression: detects task progress, completes nodes, hands out rewards and sends each player their
 * progress. Loader modules forward ticks, kills, block breaks and screen actions here.
 * <p>
 * Detection follows IMPLEMENTATION.md: items every 10 ticks (FTB Quests players complained about the slow scan),
 * positions/advancements/stats every 20 ticks, kills from the death event, everything else from screen actions.
 */
public final class ProgressService {

    private static ProgressRules rules = new ProgressRules(ProgressModel.of(new JsonObject()));
    private static ProgressModel rulesFor;
    /** Events collected during a tick, sent with the next progress packet. */
    private static final Map<UUID, List<String>> PENDING = new HashMap<>();
    /** true = send this tick; false = only experience changed, sent at most every 10 ticks (mining gives XP per block). */
    private static final Map<UUID, Boolean> DIRTY = new HashMap<>();
    /** Observation tasks being watched right now: player → {@code <node key>#<task index>} → consecutive ticks. */
    private static final Map<UUID, Map<String, Integer>> OBSERVED = new HashMap<>();
    /** Player progress already migrated for one loaded data model, so a save is not folded into its pool twice. */
    private static final Map<PlayerProgress, ProgressModel> MIGRATED = new java.util.WeakHashMap<>();

    private ProgressService() {
    }

    public static ProgressRules rules() {
        ProgressModel m = ProgressData.model();
        if (m != rulesFor) {
            rulesFor = m;
            rules = new ProgressRules(m);
        }
        return rules;
    }

    public static PlayerProgress progress(ServerPlayer player) {
        // the store resolves the player's current team (solo = their UUID) and folds a join/leave in on the way
        ProgressStore store = ProgressStore.get(player.server);
        UUID before = store.teamIdOf(player.getUUID());
        UUID current = TeamResolver.teamId(player.server, player.getUUID());
        PlayerProgress p = store.view(player.server, player.getUUID());
        if (!before.equals(current)) {
            teamChanged(player.server, player.getUUID(), before, current, Set.of());
        }
        ProgressModel model = ProgressData.model();
        if (MIGRATED.get(p) != model) {
            // chapters that gained points.pool after their values were saved (2026-10-20 review)
            ProgressRules.migratePools(p, model);
            MIGRATED.put(p, model);
        }
        return p;
    }

    /**
     * A player moved between teams (joined, left, was kicked, or FTB Teams changed under us). Everyone who shares
     * either team is marked for a send and has their attribute modifiers recomputed, because the shared skills changed
     * for them. {@code extra} adds players the resolver can no longer list (members of a disbanded party).
     */
    static void teamChanged(MinecraftServer server, UUID player, UUID oldTeam, UUID newTeam, java.util.Collection<UUID> extra) {
        Set<UUID> ids = new LinkedHashSet<>();
        ids.add(player);
        ids.addAll(extra);
        ids.addAll(TeamResolver.members(server, oldTeam));
        ids.addAll(TeamResolver.members(server, newTeam));
        for (UUID id : ids) {
            ServerPlayer online = server.getPlayerList().getPlayer(id);
            if (online == null) {
                continue;
            }
            DIRTY.put(id, true);
            Rewards.refreshAttributes(online, progress(online));
        }
        for (UUID id : ids) {
            ServerPlayer online = server.getPlayerList().getPlayer(id);
            if (online != null) {
                flush(online);
            }
        }
    }

    // ------------------------------------------------------------------ entry points

    /** Join or data reload: re-check everything and send the full state. */
    public static void onJoin(ServerPlayer player) {
        // the client starts with no team: never skip the first view because a previous session had the same one
        TeamSync.forget(player.getUUID());
        PlayerProgress p = progress(player);
        // drop every modifier first: ones left from a skill the team no longer has must not survive the login
        Rewards.refreshAttributes(player, p);
        recheck(player, p);
        giveBookOnce(player, p);
        markDirty(player);
        flush(player);
    }

    /**
     * The first-join quest book (2026-10-20), once per player and only while the server config asks for it. A full
     * inventory drops it at the player rather than losing it; the flag is set either way so it is not given twice.
     */
    private static void giveBookOnce(ServerPlayer player, PlayerProgress p) {
        if (p.bookGiven() || !ServerConfig.giveBookOnFirstJoin() || ModItems.questBook() == null) {
            return;
        }
        ItemStack book = new ItemStack(ModItems.questBook());
        if (!player.getInventory().add(book)) {
            player.drop(book, false);
        }
        p.setBookGiven(true);
        ProgressStore.get(player.server).setDirty();
        markDirty(player);
    }

    /** Respawning copies only base attribute values, so skill and quest attribute modifiers are put back. */
    public static void onRespawn(ServerPlayer player, boolean died) {
        PlayerProgress p = progress(player);
        Rewards.refreshAttributes(player, p);
        if (died) {
            for (ProgressModel.Chapter c : rules().model().chapters.values()) {
                if (!c.isSkills()) {
                    continue;
                }
                // "erase_on_death" (Pufferfish's Skills): the whole chapter starts over, skills and points included
                if (ProgressModel.boolOf(c.points, "erase_on_death")) {
                    PlayerCommands.eraseOnDeath(player, p, c);
                    ProgressStore.get(player.server).setDirty();
                    markDirty(player);
                    continue;
                }
                // "reset_on_death": the experience towards the next level is lost (levels and points stay)
                String key = ProgressRules.key(c);
                if (ProgressModel.boolOf(c.points, "reset_on_death") && p.xp.getOrDefault(key, 0.0) > 0) {
                    p.xp.put(key, 0.0);
                    ProgressStore.get(player.server).setDirty();
                    markDirty(player);
                }
            }
            recheck(player, p);
        }
    }

    public static void onPlayerTick(ServerPlayer player) {
        long t = player.server.getTickCount();
        // "observation" counts consecutive ticks of looking at the target, so it is read every tick and only written
        // every 10 ticks (writing every tick would send a progress packet every tick)
        scanObservation(player, t % 10 == 0);
        if (t % 10 == 0) {
            PlayerProgress p = progress(player);
            scanItems(player, p);
            if (t % 20 == 0) {
                scanWorld(player, p);
            }
            recheck(player, p);
        }
        flush(player);
    }

    /** Runs every periodic check now (inventory, position, advancements, stats) and sends the result. */
    public static void checkNow(ServerPlayer player) {
        PlayerProgress p = progress(player);
        scanItems(player, p);
        scanObservation(player, true);
        scanWorld(player, p);
        recheck(player, p);
        flush(player);
    }

    public static void onKill(ServerPlayer player, Entity killed) {
        PlayerProgress p = progress(player);
        ProgressRules r = rules();
        // which tasks may move is read before anything is bumped: one kill must not finish two sequential steps
        List<TaskRef> hits = new ArrayList<>();
        for (ProgressModel.Node n : r.model().nodes.values()) {
            if (!working(r, p, n)) {
                continue;
            }
            for (int i = 0; i < n.tasks.size(); i++) {
                JsonObject t = n.tasks.get(i);
                if (r.taskOpen(p, n, i) && type(t).equals("kill") && entityMatches(player, t, killed)) {
                    hits.add(new TaskRef(n, i));
                }
            }
        }
        for (TaskRef hit : hits) {
            ProgressModel.Node n = hit.node();
            int i = hit.index();
            bump(player, p, n, i, p.task(n.key, i) + 1);
        }
        SkillExperience.onKill(player, p, killed);
        recheck(player, p);
    }

    /** A block was broken (survival or creative). Counts "break" tasks, capped at the target. */
    public static void onBlockBreak(ServerPlayer player, BlockState state) {
        ProgressRules r = rules();
        List<TaskRef> refs = tasksOfType(r, "break");
        if (refs.isEmpty()) {
            return;
        }
        PlayerProgress p = progress(player);
        List<TaskRef> hits = new ArrayList<>();
        for (TaskRef ref : refs) {
            ProgressModel.Node n = ref.node();
            int i = ref.index();
            JsonObject t = n.tasks.get(i);
            if (working(r, p, n) && r.taskOpen(p, n, i) && blockMatches(blockSpec(t), state)) {
                hits.add(ref);
            }
        }
        for (TaskRef ref : hits) {
            ProgressModel.Node n = ref.node();
            int i = ref.index();
            long target = ProgressRules.target(n.tasks.get(i));
            long had = p.task(n.key, i);
            if (had < target) {
                bump(player, p, n, i, Math.min(had + 1, target));
            }
        }
        recheck(player, p);
    }

    // ------------------------------------------------------------------ interaction tasks (2026-10-21)

    private static final Map<Animal, UUID> BREEDERS = new WeakHashMap<>();
    private static final Map<String, Optional<CompoundTag>> NBT_FILTERS = new HashMap<>();

    /** A crafted result was taken out of a crafting grid. Counts the result's size, so one recipe can finish "4 planks". */
    public static void onCraft(ServerPlayer player, ItemStack stack) {
        if (stack != null && !stack.isEmpty()) {
            interaction(player, "craft", stack, null, null, null, stack.getCount());
        }
    }

    /** An item was used (food, potion, bucket, bow, ...). Counts the action once, not the stack size. */
    public static void onUseItem(ServerPlayer player, ItemStack stack) {
        if (stack != null && !stack.isEmpty()) {
            interaction(player, "use_item", stack, null, null, null, 1);
        }
    }

    /** A block was right-clicked (placed against, opened, or used). */
    public static void onRightClickBlock(ServerPlayer player, BlockState state) {
        if (state != null) {
            interaction(player, "right_click_block", null, state, null, null, 1);
        }
    }

    /** A player interacted with an entity (right-click, including feeding and mounting). */
    public static void onInteractEntity(ServerPlayer player, Entity target) {
        if (target != null) {
            interaction(player, "interact_entity", null, null, target, null, 1);
        }
    }

    public static void onTame(ServerPlayer player, Entity target) {
        if (target != null) {
            interaction(player, "tame", null, null, target, null, 1);
        }
    }

    /** Remember who last fed an animal, so the breeding event can credit that player. */
    public static void rememberBreeder(Animal animal, ServerPlayer player) {
        if (animal != null && player != null) {
            BREEDERS.put(animal, player.getUUID());
        }
    }

    /** Two animals bred; the player who fed either one gets the credit. */
    public static void onBreed(ServerLevel level, Animal first, Animal second) {
        UUID id = BREEDERS.get(first);
        if (id == null) {
            id = BREEDERS.get(second);
        }
        if (id == null) {
            return;
        }
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
        if (player != null) {
            interaction(player, "breed", null, null, first, second, 1);
        }
    }

    /** A player mounted an entity. */
    public static void onRide(ServerPlayer player, Entity vehicle) {
        if (vehicle != null) {
            interaction(player, "ride", null, null, vehicle, null, 1);
        }
    }

    /** An item entity was picked up. Counts the picked-up size. */
    public static void onPickup(ServerPlayer player, ItemStack stack) {
        if (stack != null && !stack.isEmpty()) {
            interaction(player, "pickup", stack, null, null, null, stack.getCount());
        }
    }

    /**
     * Counts one interaction task. {@code kind} is the concrete type (craft, use_item, ...); the generic
     * {@code interaction} type is included when its {@code action} matches. Every field is optional: an empty item,
     * block or entity matches any of them, as in the other task types.
     */
    private static void interaction(ServerPlayer player, String kind, ItemStack stack, BlockState state,
                                    Entity entity, Entity other, int amount) {
        if (amount <= 0) {
            return;
        }
        ProgressRules r = rules();
        List<TaskRef> refs = new ArrayList<>(tasksOfType(r, kind));
        refs.addAll(tasksOfType(r, "interaction"));
        if (refs.isEmpty()) {
            return;
        }
        PlayerProgress p = progress(player);
        List<TaskRef> hits = new ArrayList<>();
        for (TaskRef ref : refs) {
            ProgressModel.Node n = ref.node();
            int i = ref.index();
            JsonObject t = n.tasks.get(i);
            if (!working(r, p, n) || !r.taskOpen(p, n, i) || !conditionsMet(player, p, t)) {
                continue;
            }
            String wanted = type(t).equals("interaction") ? interactionKind(t) : type(t);
            if (!kind.equals(wanted)) {
                continue;
            }
            if (interactionMatches(player, t, kind, stack, state, entity, other)) {
                hits.add(ref);
            }
        }
        for (TaskRef ref : hits) {
            ProgressModel.Node n = ref.node();
            int i = ref.index();
            long target = ProgressRules.target(n.tasks.get(i));
            long had = p.task(n.key, i);
            if (had < target) {
                bump(player, p, n, i, Math.min(had + amount, target));
            }
        }
        if (!hits.isEmpty()) {
            recheck(player, p);
        }
    }

    /** Conditions are added in the next step; a task without conditions always passes. */
    static boolean conditionsMet(ServerPlayer player, PlayerProgress p, JsonObject holder) {
        return true;
    }

    private static String interactionKind(JsonObject t) {
        String action = str(t, "action");
        return action.isEmpty() ? str(t, "kind") : action;
    }

    private static boolean interactionMatches(ServerPlayer player, JsonObject t, String kind, ItemStack stack,
                                              BlockState state, Entity entity, Entity other) {
        return switch (kind) {
            case "craft", "use_item", "pickup" -> !t.has("item") || stack != null && ItemRequirement.of(t).matches(stack, player.registryAccess());
            case "right_click_block" -> state != null && blockMatches(blockSpec(t), state);
            default -> {
                if (entity == null) {
                    yield false;
                }
                yield entityMatches(player, t, entity) || other != null && entityMatches(player, t, other);
            }
        };
    }

    /** A kill (or interaction) task's entity filters: type/tag, custom name, and an optional SNBT subset. */
    static boolean entityMatches(ServerPlayer player, JsonObject task, Entity entity) {
        String tag = str(task, "entity_tag");
        if (!tag.isEmpty()) {
            if (!entityTypeTagMatches(tag.startsWith("#") ? tag : "#" + tag, entity.getType())) {
                return false;
            }
        } else if (!entityMatches(str(task, "entity"), entity.getType())) {
            return false;
        }
        String name = str(task, "name");
        if (!name.isEmpty()) {
            String actual = entity instanceof ServerPlayer other
                    ? other.getGameProfile().getName()
                    : entity.getCustomName() == null ? "" : entity.getCustomName().getString();
            if (!name.equals(actual)) {
                return false;
            }
        }
        String nbt = str(task, "nbt");
        if (!nbt.isEmpty()) {
            Optional<CompoundTag> filter = NBT_FILTERS.computeIfAbsent(nbt, text -> {
                try {
                    return Optional.of(TagParser.parseTag(text));
                } catch (CommandSyntaxException e) {
                    return Optional.empty();
                }
            });
            if (filter.isEmpty() || !NbtUtils.compareNbt(filter.get(), entity.saveWithoutId(new CompoundTag()), true)) {
                return false;
            }
        }
        return true;
    }

    public static void onAction(ServerPlayer player, ActionPayload a) {
        PlayerProgress p = progress(player);
        ProgressRules r = rules();
        ProgressModel.Node n = r.model().nodes.get(a.node());
        if (n != null && !ProgressRules.chapterUnlocked(p, n.chapter)) {
            flush(player);
            return;
        }
        switch (a.action()) {
            case "claim" -> {
                if (n != null) {
                    claim(player, p, n, a.index());
                }
            }
            case "claim_reward" -> {
                if (n != null) {
                    claimOne(player, p, n, a.index(), a.choice());
                }
            }
            case "claim_all", "claim_chapter" -> {
                // claim_chapter names a chapter in the node field: the header's button claims that chapter only
                // (2026-10-20); claim_all takes every chapter
                String only = a.action().equals("claim_chapter") ? a.node() : null;
                for (ProgressModel.Node m : r.model().nodes.values()) {
                    if (r.state(p, m) == State.CLAIM && !m.chapter.isSkills() && !ProgressModel.excludedFromClaimAll(m)
                            && (only == null || m.chapter.id.equals(only))) {
                        claim(player, p, m, -1);
                    }
                }
            }
            case "take" -> {
                if (n != null && r.take(p, n)) {
                    Rewards.giveSkill(player, p, n, p.taken.getOrDefault(n.key, 1) - 1);
                    event(player, p, "take", n.key);
                }
            }
            case "submit" -> {
                if (n != null && working(r, p, n) && validTask(n, a.index()) && r.taskOpen(p, n, a.index())
                        && ProgressRules.consumes(n, n.tasks.get(a.index()))) {
                    submit(player, p, n, a.index());
                }
            }
            case "check" -> {
                if (n != null && working(r, p, n) && validTask(n, a.index()) && r.taskOpen(p, n, a.index())
                        && manual(n.tasks.get(a.index()))) {
                    JsonObject task = n.tasks.get(a.index());
                    long max = ProgressRules.target(task);
                    long current = p.task(n.key, a.index());
                    long next = com.kuronami.questtree.progress.CustomTaskResult.buttonProgress(current, max);
                    if (type(task).equals("custom")) {
                        // FTB Quests' custom task is Java code registered by an addon: a listener may set the progress
                        // or complete it. Only with no listener for the task does the button itself make progress, as
                        // before; a listener that leaves it alone is still in charge (2026-10-20 review).
                        boolean listened = QuestEvents.hasListener(QuestEvents.taskKey(n, task, a.index()))
                                || QuestEvents.hasAnyListener();
                        var result = QuestEvents.fire(player, n, task, true, a.index(), current, max);
                        next = listened ? (result.handled() ? result.progress() : current) : next;
                    }
                    if (next != current) {
                        bump(player, p, n, a.index(), next);
                    }
                }
            }
            case "exchange" -> {
                // the skills screen's buy button: spend vanilla levels for one point (DATA_MODEL points.exchange)
                ProgressModel.Chapter c = r.model().chapters.get(a.node());
                if (c != null) {
                    exchange(player, p, c);
                }
            }
            default -> Constants.LOG.debug("Unknown progression action {}", a.action());
        }
        recheck(player, p);
        flush(player);
    }

    /**
     * One exchange purchase: the chapter's {@code points.exchange.xp_levels} vanilla levels become one point, up to
     * {@code level_limit} purchases (2026-10-20). Pufferfish's Skills computes a cost per exchange level; the data
     * model keeps a fixed cost, which is what packs write.
     */
    private static void exchange(ServerPlayer player, PlayerProgress p, ProgressModel.Chapter c) {
        // the limit is counted per pool key, and a locked chapter cannot be bought into: the action names a chapter,
        // so the node-level lock check at the top of onAction never saw it (2026-10-20 review)
        if (!ProgressRules.canExchange(p, c, player.experienceLevel)) {
            return;
        }
        player.giveExperienceLevels(-ProgressRules.exchangeOf(c).cost());
        ProgressRules.applyExchange(p, c);
        ProgressStore.get(player.server).setDirty();
        markDirty(player);
    }

    // ------------------------------------------------------------------ detection

    /** Tasks a click can move: FTB Quests' {@code checkmark}, and {@code custom} when it enables its button. */
    private static boolean manual(JsonObject task) {
        String type = type(task);
        return type.equals("checkmark") || type.equals("custom")
                && task.has("enable_button") && task.get("enable_button").getAsBoolean();
    }

    /** Whether the node's tasks may move now: visible, unlocked, not excluded, not done. */
    private static boolean working(ProgressRules r, PlayerProgress p, ProgressModel.Node n) {
        if (!ProgressRules.chapterUnlocked(p, n.chapter) || n.tasks.isEmpty() || p.completed.contains(n.key)) {
            return false;
        }
        State s = r.state(p, n);
        if (s == State.EXCLUDED) {
            return false;
        }
        return s != State.LOCKED;
    }

    private static void scanItems(ServerPlayer player, PlayerProgress p) {
        ProgressRules r = rules();
        Map<String, Long> held = null;
        // FTBQ counts what a member holds toward the team's value and only ever raises it (ItemTask.submitTask), so one
        // member with an empty bag cannot undo another's count. A solo player keeps the "what you hold now" rule.
        boolean team = TeamResolver.members(player.server, TeamResolver.teamId(player.server, player.getUUID())).size() > 1;
        for (ProgressModel.Node n : r.model().nodes.values()) {
            if (!working(r, p, n)) {
                continue;
            }
            for (int i = 0; i < n.tasks.size(); i++) {
                JsonObject t = n.tasks.get(i);
                if (ProgressRules.consumes(n, t) || !r.taskOpen(p, n, i)) {
                    continue;
                }
                String ty = type(t);
                // what the task measures right now: items held, fluid held, or the largest single energy store carried
                String key;
                long target = ProgressRules.target(t);
                switch (ty) {
                    case "item" -> key = "i:" + ItemRequirement.of(t).key();
                    case "fluid" -> key = "f:" + fluidSpec(t);
                    case "energy" -> key = "e";
                    default -> {
                        continue;
                    }
                }
                if (held == null) {
                    held = new HashMap<>();
                }
                Long now = held.get(key);
                if (now == null) {
                    now = switch (ty) {
                        case "fluid" -> inventoryFluid(player, fluidSpec(t));
                        case "energy" -> inventoryEnergy(player);
                        default -> (long) countItems(player, ItemRequirement.of(t));
                    };
                    held.put(key, now);
                }
                // the count is what you hold now: it goes down when items are crafted away, so it never looks as if
                // every log picked up added to it (2026-10-16, the player read a stuck 8/16 that way). Counting items
                // "obtained" instead was rejected: drops, chests, machines and reversible recipes all give ways to
                // inflate it that no one can foresee. Reaching the target once still finishes the task, like FTB Quests
                long had = p.task(n.key, i);
                long capped = Math.min(now, target);
                if (had < target && (team ? capped > had : capped != had)) {
                    bump(player, p, n, i, capped);
                }
            }
        }
    }

    /**
     * Watches "observation" tasks: how many ticks in a row the player has kept the target under the crosshair. A miss
     * resets the run, like FTB Quests. Progress is written every 10 ticks ({@code persist}), and immediately once a
     * run reaches the target so the task finishes without the player having to look away.
     */
    private static void scanObservation(ServerPlayer player, boolean persist) {
        ProgressRules r = rules();
        List<TaskRef> refs = tasksOfType(r, "observation");
        if (refs.isEmpty()) {
            return;
        }
        PlayerProgress p = progress(player);
        for (TaskRef ref : refs) {
            ProgressModel.Node n = ref.node();
            int i = ref.index();
            JsonObject t = n.tasks.get(i);
            if (!working(r, p, n)) {
                continue;
            }
            if (!r.taskOpen(p, n, i)) {
                continue;
            }
            long target = ProgressRules.target(t);
            if (p.task(n.key, i) >= target) {
                continue;
            }
            String key = n.key + "#" + i;
            Map<String, Integer> seen = OBSERVED.computeIfAbsent(player.getUUID(), k -> new HashMap<>());
            int run = observing(player, t) ? Math.min(seen.getOrDefault(key, 0) + 1, (int) target) : 0;
            seen.put(key, run);
            if (persist || run >= target) {
                if (p.task(n.key, i) != run) {
                    // quiet write: a look that lasts a while would otherwise send a packet every tick
                    p.setTask(n.key, i, run);
                    ProgressStore.get(player.server).setDirty();
                    markExperienceDirty(player);
                }
                if (run >= target) {
                    recheck(player, p);
                }
            }
        }
    }

    /** One task of one node. */
    private record TaskRef(ProgressModel.Node node, int index) {
    }

    private static ProgressModel taskIndexModel;
    private static final Map<String, List<TaskRef>> TASKS_BY_TYPE = new HashMap<>();

    /**
     * The tasks of one type across the loaded data, rebuilt when the data reloads. Observation is read every tick and a
     * break on every block, so they walk this short list instead of every node of a pack (ATM10 has about 4,800).
     */
    private static List<TaskRef> tasksOfType(ProgressRules r, String type) {
        if (taskIndexModel != r.model()) {
            TASKS_BY_TYPE.clear();
            taskIndexModel = r.model();
        }
        return TASKS_BY_TYPE.computeIfAbsent(type, ty -> {
            List<TaskRef> out = new ArrayList<>();
            for (ProgressModel.Node n : r.model().nodes.values()) {
                for (int i = 0; i < n.tasks.size(); i++) {
                    if (type(n.tasks.get(i)).equals(ty)) {
                        out.add(new TaskRef(n, i));
                    }
                }
            }
            return out;
        });
    }

    /** Whether the player is looking at the task's target right now. */
    private static boolean observing(ServerPlayer player, JsonObject t) {
        String kind = str(t, "observe_type");
        String target = str(t, "target");
        if (target.isEmpty()) {
            return false;
        }
        if (kind.isEmpty()) {
            kind = "block";
        }
        return switch (kind) {
            case "block", "block_tag", "block_state" -> {
                double reach = player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
                HitResult hit = player.pick(reach, 1.0F, false);
                yield hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult b
                        && blockMatches(player, kind, target, player.level().getBlockState(b.getBlockPos()));
            }
            case "entity_type", "entity_type_tag" -> {
                double reach = player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
                Vec3 eye = player.getEyePosition();
                // a wall in between hides the entity: stop the look where the first block is
                HitResult wall = player.pick(reach, 1.0F, false);
                if (wall.getType() == HitResult.Type.BLOCK) {
                    reach = Math.min(reach, wall.getLocation().distanceTo(eye));
                }
                Vec3 end = eye.add(player.getViewVector(1.0F).scale(reach));
                EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, end, new AABB(eye, end).inflate(1.0),
                        e -> !e.isSpectator() && e.isPickable(), reach * reach);
                if (hit == null) {
                    yield false;
                }
                EntityType<?> type = hit.getEntity().getType();
                yield kind.equals("entity_type_tag") ? entityTypeTagMatches(target, type) : entityMatches(target, type);
            }
            default -> false;
        };
    }

    private static void scanWorld(ServerPlayer player, PlayerProgress p) {
        ProgressRules r = rules();
        ServerLevel level = player.serverLevel();
        BlockPos pos = player.blockPosition();
        for (ProgressModel.Node n : r.model().nodes.values()) {
            if (!working(r, p, n)) {
                continue;
            }
            for (int i = 0; i < n.tasks.size(); i++) {
                JsonObject t = n.tasks.get(i);
                if (!r.taskOpen(p, n, i)) {
                    continue;
                }
                long value = switch (type(t)) {
                    case "dimension" -> level.dimension().location().toString().equals(str(t, "dimension")) ? 1 : 0;
                    case "biome" -> biomeMatches(level, pos, str(t, "biome")) ? 1 : 0;
                    case "structure" -> structureMatches(level, pos, str(t, "structure")) ? 1 : 0;
                    case "location" -> inside(player, t) ? 1 : 0;
                    case "advancement" -> advancementDone(player, str(t, "advancement"), str(t, "criterion")) ? 1 : 0;
                    case "stat" -> stat(player, str(t, "stat"));
                    case "stage" -> hasStage(player, str(t, "stage")) ? 1 : 0;
                    default -> -1;
                };
                if (value < 0) {
                    continue;
                }
                long now = Math.min(value, ProgressRules.target(t));
                if (now > p.task(n.key, i)) {
                    bump(player, p, n, i, now);
                }
            }
        }
    }

    /** Hands in items (or experience) for a task that consumes them. */
    private static void submit(ServerPlayer player, PlayerProgress p, ProgressModel.Node n, int i) {
        JsonObject t = n.tasks.get(i);
        long target = ProgressRules.target(t);
        long have = p.task(n.key, i);
        if (have >= target) {
            return;
        }
        long missing = target - have;
        if (type(t).equals("item")) {
            ItemRequirement requirement = ItemRequirement.of(t);
            long taken = 0;
            var inv = player.getInventory();
            // main inventory and hotbar only: never take worn armour or the offhand item
            for (int s = 0; s < inv.items.size() && taken < missing; s++) {
                ItemStack st = inv.items.get(s);
                if (!st.isEmpty() && requirement.matches(st, player.registryAccess())) {
                    int n2 = (int) Math.min(st.getCount(), missing - taken);
                    st.shrink(n2);
                    taken += n2;
                }
            }
            if (taken > 0) {
                bump(player, p, n, i, have + taken);
                event(player, p, "submit", n.key);
            }
        } else if (type(t).equals("xp")) {
            boolean points = ProgressModel.boolOf(t, "points");
            // totalExperience is a running counter (not reduced by enchanting), so count the points the player has now
            long take = points ? Math.min(missing, currentPoints(player)) : Math.min(missing, player.experienceLevel);
            if (take > 0) {
                if (points) {
                    player.giveExperiencePoints((int) -take);
                } else {
                    player.giveExperienceLevels((int) -take);
                }
                bump(player, p, n, i, have + take);
                event(player, p, "submit", n.key);
            }
        }
    }

    private static long currentPoints(ServerPlayer player) {
        long sum = 0;
        for (int lv = 0; lv < player.experienceLevel; lv++) {
            sum += lv >= 30 ? 112 + (lv - 30) * 9 : lv >= 15 ? 37 + (lv - 15) * 5 : 7 + lv * 2;
        }
        return sum + (long) (player.experienceProgress * player.getXpNeededForNextLevel());
    }

    private static void bump(ServerPlayer player, PlayerProgress p, ProgressModel.Node n, int i, long value) {
        p.setTask(n.key, i, value);
        ProgressStore.get(player.server).setDirty();
        markDirty(player);
    }

    /**
     * Completes every node whose tasks are done, repeatedly, because completing one can open the next (and nodes
     * without tasks complete as soon as they are open, like FTB Quests).
     */
    static void recheck(ServerPlayer player, PlayerProgress p) {
        ProgressRules r = rules();
        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < 64) {
            changed = false;
            for (ProgressModel.Node n : r.model().nodes.values()) {
                if (n.chapter.isSkills() && n.tasks.isEmpty() || p.completed.contains(n.key)) {
                    continue;
                }
                State s = r.state(p, n);
                if (s == State.LOCKED || s == State.EXCLUDED || !r.unlocked(p, n)) {
                    continue;
                }
                if (r.tasksDone(p, n)) {
                    p.completed.add(n.key);
                    event(player, p, "complete", n.key);
                    autoRewards(player, p, n);
                    // nothing to claim means no claim click: the completion itself starts the quest over
                    r.repeatAfterCompletion(p, n, System.currentTimeMillis());
                    changed = true;
                }
            }
        }
        if (guard > 1) {
            ProgressStore.get(player.server).setDirty();
        }
    }

    /**
     * Rewards marked {@code "auto": true} are handed out at completion, one by one since 2026-10-20; the quest stays
     * claimable while another (manual) reward waits.
     */
    static void autoRewards(ServerPlayer player, PlayerProgress p, ProgressModel.Node n) {
        if (n.rewards.isEmpty()) {
            return;
        }
        // FTBQ Reward.forceProgress: a non-team auto reward goes to every online member, a team reward only to the
        // player whose progress completed it. Offline members get nothing, as in FTBQ (no catch-up on login)
        String repeatKey = n.key;
        int repeatsBefore = p.repeats.getOrDefault(repeatKey, 0);
        List<PlayerProgress> others = new ArrayList<>();
        for (ServerPlayer member : TeamResolver.onlineMembers(player.server, player.getUUID())) {
            if (member.getUUID().equals(player.getUUID())) {
                continue;
            }
            PlayerProgress pm = progress(member);
            giveAuto(member, pm, n, false);
            if (ProgressRules.allRewardsClaimed(pm, n)) {
                pm.claimed.add(n.key);
            }
            others.add(pm);
        }
        giveAuto(player, p, n, true);
        finishClaim(player, p, n);
        if (p.repeats.getOrDefault(repeatKey, 0) > repeatsBefore) {
            // the team's quest started over: the other members' marks for this round go with it
            for (PlayerProgress pm : others) {
                pm.claimed.remove(n.key);
                pm.claimedRewards.removeIf(k -> k.startsWith(n.key + "#"));
            }
        }
    }

    private static void giveAuto(ServerPlayer player, PlayerProgress p, ProgressModel.Node n, boolean actor) {
        for (int i = 0; i < n.rewards.size(); i++) {
            JsonObject rw = n.rewards.get(i);
            String key = n.key + "#" + i;
            if (!ProgressModel.boolOf(rw, "auto") || p.rewardClaimed(key)) {
                continue;
            }
            if (ProgressModel.boolOf(rw, "team") && !actor) {
                continue;
            }
            Rewards.give(player, p, n, rw, -1);
            markReward(p, rw, key);
            ProgressStore.get(player.server).setDirty();
        }
    }

    /**
     * Takes every reward still waiting on a completed node (the claim button and "claim all"). A choice table without
     * its option claimed yet leaves the whole node waiting, as before per-reward claims existed.
     */
    private static void claim(ServerPlayer player, PlayerProgress p, ProgressModel.Node n, int choice) {
        if (!p.completed.contains(n.key) || p.claimed.contains(n.key)) {
            return;
        }
        for (JsonObject rw : n.rewards) {
            if (choiceMissing(rw, choice)) {
                return;
            }
        }
        for (int i = 0; i < n.rewards.size(); i++) {
            String key = n.key + "#" + i;
            if (p.rewardClaimed(key)) {
                continue;
            }
            Rewards.give(player, p, n, n.rewards.get(i), choice);
            markReward(p, n.rewards.get(i), key);
        }
        ProgressStore.get(player.server).setDirty();
        finishClaim(player, p, n);
    }

    /** Takes one reward from the details sheet (FTB Quests' per-reward claim, 2026-10-20). */
    private static void claimOne(ServerPlayer player, PlayerProgress p, ProgressModel.Node n, int index, int choice) {
        if (!p.completed.contains(n.key) || p.claimed.contains(n.key) || index < 0 || index >= n.rewards.size()) {
            return;
        }
        String key = n.key + "#" + index;
        if (p.rewardClaimed(key)) {
            return;
        }
        JsonObject rw = n.rewards.get(index);
        if (choiceMissing(rw, choice)) {
            return;
        }
        Rewards.give(player, p, n, rw, choice);
        markReward(p, rw, key);
        ProgressStore.get(player.server).setDirty();
        finishClaim(player, p, n);
    }

    /**
     * Records a reward as taken. A reward with {@code "team": true} (FTBQ's team_reward) settles once for the whole
     * team; every other reward belongs to the player who claimed it.
     */
    private static void markReward(PlayerProgress p, JsonObject rw, String key) {
        if (ProgressModel.boolOf(rw, "team")) {
            p.claimTeamReward(key);
        } else {
            p.claimedRewards.add(key);
        }
    }

    /** A choice table needs the player to pick a valid option before anything on the node is claimed. */
    private static boolean choiceMissing(JsonObject rw, int choice) {
        return type(rw).equals("table") && "choice".equals(str(rw, "mode"))
                && (choice < 0 || choice >= Rewards.tableSize(rw));
    }

    /** Marks the node claimed and starts a repeat once its last reward was taken. */
    private static void finishClaim(ServerPlayer player, PlayerProgress p, ProgressModel.Node n) {
        if (p.claimed.contains(n.key) || !ProgressRules.allRewardsClaimed(p, n)) {
            return;
        }
        p.claimed.add(n.key);
        event(player, p, "claim", n.key);
        rules().repeatAfterClaim(p, n, System.currentTimeMillis());
        ProgressStore.get(player.server).setDirty();
    }

    // ------------------------------------------------------------------ sending

    /**
     * One event for every online member of the team, so a completion or claim is seen by everyone who shares it.
     * The event also goes into each member's recent list (stored by UUID, so an offline member keeps it).
     */
    static void event(ServerPlayer player, PlayerProgress p, String kind, String node) {
        MinecraftServer server = player.server;
        UUID teamId = TeamResolver.teamId(server, player.getUUID());
        TeamProgress team = ProgressStore.get(server).team(teamId);
        long now = System.currentTimeMillis();
        Set<UUID> members = new LinkedHashSet<>(TeamResolver.members(server, teamId));
        members.add(player.getUUID());
        for (UUID id : members) {
            team.part(id).event(kind, node, now);
            ServerPlayer online = server.getPlayerList().getPlayer(id);
            if (online != null) {
                PENDING.computeIfAbsent(id, k -> new ArrayList<>()).add(kind + "|" + node + "|" + player.getUUID());
                DIRTY.put(id, true);
            }
        }
        ProgressStore.get(server).setDirty();
    }

    /** Marks every online member of the player's team, because the change was to shared progress. */
    static void markDirty(ServerPlayer player) {
        for (ServerPlayer member : TeamResolver.onlineMembers(player.server, player.getUUID())) {
            DIRTY.put(member.getUUID(), true);
        }
    }

    /** Experience is shared too: every member is marked, but only for the 10-tick (non-urgent) send. */
    static void markExperienceDirty(ServerPlayer player) {
        for (ServerPlayer member : TeamResolver.onlineMembers(player.server, player.getUUID())) {
            DIRTY.putIfAbsent(member.getUUID(), false);
        }
    }

    /** Sends the current shared progress to every online member now (used after a command or a party change). */
    static void flushTeam(ServerPlayer player) {
        for (ServerPlayer member : TeamResolver.onlineMembers(player.server, player.getUUID())) {
            flush(member);
        }
    }

    /** Sends the player's progress if anything changed since the last send. */
    public static void flush(ServerPlayer player) {
        Boolean urgent = DIRTY.get(player.getUUID());
        if (urgent == null || (!urgent && player.server.getTickCount() % 10 != 0)) {
            return;
        }
        DIRTY.remove(player.getUUID());
        List<String> events = PENDING.remove(player.getUUID());
        if (events == null) {
            events = List.of();
        } else if (events.size() > 64) {
            events = events.subList(events.size() - 64, events.size());
        }
        ProgressPayload payload = ProgressPayload.of(ProgressJson.write(progress(player)).toString(), List.copyOf(events));
        if (payload.data().length > ProgressPayload.MAX) {
            Constants.LOG.error("Progress of {} is too large to send ({} bytes compressed)", player.getGameProfile().getName(), payload.data().length);
            return;
        }
        Services.PLATFORM.sendToPlayer(player, payload);
        // the party row and the shared-quest labels come from the same update as the progress they describe
        TeamSync.send(player);
    }

    public static void onLogout(ServerPlayer player) {
        PENDING.remove(player.getUUID());
        DIRTY.remove(player.getUUID());
        OBSERVED.remove(player.getUUID());
        TeamSync.forget(player.getUUID());
        // logout fires before the server removes the player, so they must be excluded from "online" explicitly
        TeamSync.broadcast(player.server, player.getUUID());
    }

    /** Server stopping: the anti-farming windows are runtime state and must not outlive the server (2026-10-20). */
    public static void onServerStopping() {
        SkillExperience.clearState();
        PENDING.clear();
        DIRTY.clear();
        OBSERVED.clear();
        MIGRATED.clear();
        TeamSync.clear();
    }

    // ------------------------------------------------------------------ matching helpers

    static String type(JsonObject o) {
        return o.has("type") ? o.get("type").getAsString() : "";
    }

    static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    private static boolean validTask(ProgressModel.Node n, int i) {
        return i >= 0 && i < n.tasks.size();
    }

    /**
     * An item task's matcher as one string: an item id, {@code #tag}, or {@code ~expression} for an FTB Filter System
     * smart filter. FTB Quests packs (ATM10: 437 tasks) ask for "any of these" through a smart filter item; until
     * 2026-10-20 its id was compared as it stood, so only the filter item itself counted and those tasks could not be done.
     */
    static String itemSpec(JsonObject task) {
        return ItemRequirement.of(task).spec();
    }

    /** A fluid task's matcher as one string: a fluid id, or {@code #tag}. */
    static String fluidSpec(JsonObject task) {
        return spec(task.get("fluid"));
    }

    /** A block task's matcher as one string: a block id, or {@code #tag} (empty = any block). */
    static String blockSpec(JsonObject task) {
        return spec(task.get("block"));
    }

    private static String spec(JsonElement e) {
        if (e == null) {
            return "";
        }
        if (e.isJsonPrimitive()) {
            return e.getAsString();
        }
        JsonObject o = e.getAsJsonObject();
        if (o.has("tag")) {
            return "#" + o.get("tag").getAsString();
        }
        if (o.has("id")) {
            return o.get("id").getAsString();
        }
        return o.has("fluid") ? o.get("fluid").getAsString() : "";
    }

    private static int countItems(ServerPlayer player, ItemRequirement requirement) {
        int c = 0;
        var inv = player.getInventory();
        for (int s = 0; s < inv.getContainerSize(); s++) {
            ItemStack st = inv.getItem(s);
            if (requirement.matches(st, player.registryAccess())) {
                c += st.getCount();
            }
        }
        return c;
    }

    /** Fluid in mB held across the inventory (buckets count as 1000). */
    private static long inventoryFluid(ServerPlayer player, String spec) {
        if (spec.isEmpty()) {
            return 0;
        }
        long total = 0;
        var inv = player.getInventory();
        for (int s = 0; s < inv.getContainerSize(); s++) {
            ItemStack st = inv.getItem(s);
            if (!st.isEmpty()) {
                total += Services.ITEM_STORAGE.fluidAmount(st, spec);
            }
        }
        return total;
    }

    /** The energy of the fullest single carried item (FE), not the sum: FTB Quests reads one battery at a time. */
    private static long inventoryEnergy(ServerPlayer player) {
        long max = 0;
        var inv = player.getInventory();
        for (int s = 0; s < inv.getContainerSize(); s++) {
            ItemStack st = inv.getItem(s);
            if (!st.isEmpty()) {
                max = Math.max(max, Services.ITEM_STORAGE.energyStored(st));
            }
        }
        return max;
    }

    /** A "break" task's block matcher; an empty spec matches any block. */
    static boolean blockMatches(String spec, BlockState state) {
        if (spec.isEmpty()) {
            return true;
        }
        if (spec.startsWith("#")) {
            return blockTagMatches(spec, state.getBlock());
        }
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(spec);
    }

    /** An "observation" task's block matcher, where {@code kind} picks id, tag or exact block state. */
    private static boolean blockMatches(ServerPlayer player, String kind, String spec, BlockState state) {
        return switch (kind) {
            case "block_tag" -> blockTagMatches(spec, state.getBlock());
            case "block_state" -> {
                try {
                    yield BlockStateParser.parseForBlock(player.level().holderLookup(Registries.BLOCK), spec, false).blockState() == state;
                } catch (CommandSyntaxException e) {
                    yield false;
                }
            }
            default -> blockMatches(spec, state);
        };
    }

    private static boolean blockTagMatches(String spec, Block block) {
        String id = spec.startsWith("#") ? spec.substring(1) : spec;
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl != null && block.builtInRegistryHolder().is(TagKey.create(Registries.BLOCK, rl));
    }

    private static boolean entityTypeTagMatches(String spec, EntityType<?> type) {
        String id = spec.startsWith("#") ? spec.substring(1) : spec;
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl != null && type.is(TagKey.create(Registries.ENTITY_TYPE, rl));
    }

    static boolean entityMatches(String spec, EntityType<?> type) {
        if (spec.isEmpty()) {
            return true;
        }
        if (spec.startsWith("#")) {
            ResourceLocation id = ResourceLocation.tryParse(spec.substring(1));
            return id != null && type.is(TagKey.create(Registries.ENTITY_TYPE, id));
        }
        return BuiltInRegistries.ENTITY_TYPE.getKey(type).toString().equals(spec);
    }

    private static boolean biomeMatches(ServerLevel level, BlockPos pos, String spec) {
        if (spec.isEmpty()) {
            return false;
        }
        var biome = level.getBiome(pos);
        if (spec.startsWith("#")) {
            ResourceLocation id = ResourceLocation.tryParse(spec.substring(1));
            return id != null && biome.is(TagKey.create(Registries.BIOME, id));
        }
        ResourceLocation id = ResourceLocation.tryParse(spec);
        return id != null && biome.is(id);
    }

    private static boolean structureMatches(ServerLevel level, BlockPos pos, String spec) {
        if (spec.isEmpty() || !level.isLoaded(pos)) {
            return false;
        }
        if (spec.startsWith("#")) {
            ResourceLocation id = ResourceLocation.tryParse(spec.substring(1));
            return id != null && level.structureManager().getStructureWithPieceAt(pos, TagKey.create(Registries.STRUCTURE, id)).isValid();
        }
        ResourceLocation id = ResourceLocation.tryParse(spec);
        Structure s = id == null ? null : level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(id);
        return s != null && level.structureManager().getStructureWithPieceAt(pos, s).isValid();
    }

    private static boolean inside(ServerPlayer player, JsonObject t) {
        String dim = str(t, "dimension");
        if (!dim.isEmpty() && !ProgressModel.boolOf(t, "ignore_dimension")
                && !player.level().dimension().location().toString().equals(dim)) {
            return false;
        }
        int[] at = ints(t.get("position"));
        int[] size = ints(t.get("size"));
        if (at.length < 3) {
            return false;
        }
        int sx = size.length >= 3 ? size[0] : 1;
        int sy = size.length >= 3 ? size[1] : 1;
        int sz = size.length >= 3 ? size[2] : 1;
        BlockPos p = player.blockPosition();
        return p.getX() >= at[0] && p.getX() < at[0] + sx && p.getY() >= at[1] && p.getY() < at[1] + sy
                && p.getZ() >= at[2] && p.getZ() < at[2] + sz;
    }

    private static int[] ints(JsonElement e) {
        if (e == null || !e.isJsonArray()) {
            return new int[0];
        }
        JsonArray a = e.getAsJsonArray();
        int[] out = new int[a.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = a.get(i).getAsInt();
        }
        return out;
    }

    private static boolean advancementDone(ServerPlayer player, String id, String criterion) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        MinecraftServer server = player.server;
        AdvancementHolder h = rl == null ? null : server.getAdvancements().get(rl);
        if (h == null) {
            return false;
        }
        AdvancementProgress prog = player.getAdvancements().getOrStartProgress(h);
        if (criterion.isEmpty()) {
            return prog.isDone();
        }
        var c = prog.getCriterion(criterion);
        return c != null && c.isDone();
    }

    private static long stat(ServerPlayer player, String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null || !BuiltInRegistries.CUSTOM_STAT.containsKey(rl)) {
            return 0;
        }
        return player.getStats().getValue(Stats.CUSTOM.get(BuiltInRegistries.CUSTOM_STAT.get(rl)));
    }

    /** Pack Stages (mod-100) is driven through its command so neither mod depends on the other. */
    static boolean hasStage(ServerPlayer player, String stage) {
        if (stage.isEmpty() || !Services.PLATFORM.isModLoaded("pack_stages")) {
            return false;
        }
        return ServerCommands.run(player, "pack_stages has " + player.getGameProfile().getName() + " " + stage, 2, true) > 0;
    }
}
