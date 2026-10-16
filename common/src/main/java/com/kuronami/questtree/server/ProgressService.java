package com.kuronami.questtree.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kuronami.questtree.Constants;
import com.kuronami.questtree.data.ProgressModel;
import com.kuronami.questtree.network.ActionPayload;
import com.kuronami.questtree.network.ProgressPayload;
import com.kuronami.questtree.platform.Services;
import com.kuronami.questtree.progress.PlayerProgress;
import com.kuronami.questtree.progress.ProgressJson;
import com.kuronami.questtree.progress.ProgressRules;
import com.kuronami.questtree.progress.ProgressRules.State;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        return ProgressStore.get(player.server).of(player.getUUID());
    }

    // ------------------------------------------------------------------ entry points

    /** Join or data reload: re-check everything and send the full state. */
    public static void onJoin(ServerPlayer player) {
        Rewards.reapplyAttributes(player, progress(player));
        recheck(player, progress(player));
        markDirty(player);
        flush(player);
    }

    /** Respawning copies only base attribute values, so skill and quest attribute modifiers are put back. */
    public static void onRespawn(ServerPlayer player, boolean died) {
        PlayerProgress p = progress(player);
        Rewards.reapplyAttributes(player, p);
        if (died) {
            // "reset_on_death": the experience towards the next level is lost (levels and points stay)
            for (ProgressModel.Chapter c : rules().model().chapters.values()) {
                if (c.isSkills() && ProgressModel.boolOf(c.points, "reset_on_death") && p.xp.getOrDefault(c.id, 0.0) > 0) {
                    p.xp.put(c.id, 0.0);
                    ProgressStore.get(player.server).setDirty();
                    markDirty(player);
                }
            }
        }
    }

    public static void onPlayerTick(ServerPlayer player) {
        long t = player.server.getTickCount();
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
        scanWorld(player, p);
        recheck(player, p);
        flush(player);
    }

    public static void onKill(ServerPlayer player, Entity killed) {
        PlayerProgress p = progress(player);
        ProgressRules r = rules();
        for (ProgressModel.Node n : r.model().nodes.values()) {
            if (!working(r, p, n)) {
                continue;
            }
            for (int i = 0; i < n.tasks.size(); i++) {
                JsonObject t = n.tasks.get(i);
                if (type(t).equals("kill") && entityMatches(str(t, "entity"), killed.getType())) {
                    bump(player, p, n, i, p.task(n.key, i) + 1);
                }
            }
        }
        SkillExperience.onKill(player, p, killed);
        recheck(player, p);
    }

    public static void onAction(ServerPlayer player, ActionPayload a) {
        PlayerProgress p = progress(player);
        ProgressRules r = rules();
        ProgressModel.Node n = r.model().nodes.get(a.node());
        switch (a.action()) {
            case "claim" -> {
                if (n != null) {
                    claim(player, p, n, a.index());
                }
            }
            case "claim_all" -> {
                for (ProgressModel.Node m : r.model().nodes.values()) {
                    if (r.state(p, m) == State.CLAIM && !m.chapter.isSkills() && !excludedFromClaimAll(m)) {
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
                if (n != null && working(r, p, n) && validTask(n, a.index()) && ProgressRules.consumes(n, n.tasks.get(a.index()))) {
                    submit(player, p, n, a.index());
                }
            }
            case "check" -> {
                if (n != null && working(r, p, n) && validTask(n, a.index()) && type(n.tasks.get(a.index())).equals("checkmark")) {
                    bump(player, p, n, a.index(), 1);
                }
            }
            default -> Constants.LOG.debug("Unknown progression action {}", a.action());
        }
        recheck(player, p);
        flush(player);
    }

    // ------------------------------------------------------------------ detection

    /** Whether the node's tasks may move now: visible, unlocked, not excluded, not done. */
    private static boolean working(ProgressRules r, PlayerProgress p, ProgressModel.Node n) {
        if (n.tasks.isEmpty() || p.completed.contains(n.key)) {
            return false;
        }
        State s = r.state(p, n);
        if (s == State.EXCLUDED) {
            return false;
        }
        return s != State.LOCKED || ProgressModel.boolOf(n.json, "tasks_ignore_requires");
    }

    private static void scanItems(ServerPlayer player, PlayerProgress p) {
        ProgressRules r = rules();
        Map<String, Integer> counts = null;
        for (ProgressModel.Node n : r.model().nodes.values()) {
            if (!working(r, p, n)) {
                continue;
            }
            for (int i = 0; i < n.tasks.size(); i++) {
                JsonObject t = n.tasks.get(i);
                if (!type(t).equals("item") || ProgressRules.consumes(n, t)) {
                    continue;
                }
                if (counts == null) {
                    counts = new HashMap<>();
                }
                String spec = itemSpec(t);
                Integer held = counts.get(spec);
                if (held == null) {
                    held = countItems(player, spec);
                    counts.put(spec, held);
                }
                // the count is what you hold now: it goes down when items are crafted away, so it never looks as if
                // every log picked up added to it (2026-10-16, the player read a stuck 8/16 that way). Counting items
                // "obtained" instead was rejected: drops, chests, machines and reversible recipes all give ways to
                // inflate it that no one can foresee. Reaching the target once still finishes the task, like FTB Quests
                long target = ProgressRules.target(t);
                long had = p.task(n.key, i);
                long now = Math.min(held, target);
                if (had < target && now != had) {
                    bump(player, p, n, i, now);
                }
            }
        }
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
            String spec = itemSpec(t);
            long taken = 0;
            var inv = player.getInventory();
            // main inventory and hotbar only: never take worn armour or the offhand item
            for (int s = 0; s < inv.items.size() && taken < missing; s++) {
                ItemStack st = inv.items.get(s);
                if (!st.isEmpty() && itemMatches(spec, st)) {
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
                if (s == State.LOCKED || s == State.EXCLUDED) {
                    continue;
                }
                if (r.tasksDone(p, n)) {
                    p.completed.add(n.key);
                    event(player, p, "complete", n.key);
                    autoRewards(player, p, n);
                    changed = true;
                }
            }
        }
        if (guard > 1) {
            ProgressStore.get(player.server).setDirty();
        }
    }

    static void autoRewards(ServerPlayer player, PlayerProgress p, ProgressModel.Node n) {
        if (n.rewards.isEmpty()) {
            return;
        }
        boolean allAuto = n.rewards.stream().allMatch(rw -> ProgressModel.boolOf(rw, "auto"));
        if (allAuto) {
            claim(player, p, n, -1);
        }
    }

    private static void claim(ServerPlayer player, PlayerProgress p, ProgressModel.Node n, int choice) {
        if (!p.completed.contains(n.key) || p.claimed.contains(n.key)) {
            return;
        }
        // a choice table needs the player to pick first; claiming without a pick leaves the node claimable
        for (JsonObject rw : n.rewards) {
            if (type(rw).equals("table") && "choice".equals(str(rw, "mode")) && (choice < 0 || choice >= Rewards.tableSize(rw))) {
                return;
            }
        }
        p.claimed.add(n.key);
        for (JsonObject rw : n.rewards) {
            Rewards.give(player, p, n, rw, choice);
        }
        event(player, p, "claim", n.key);
        rules().repeatAfterClaim(p, n, System.currentTimeMillis());
        ProgressStore.get(player.server).setDirty();
    }

    private static boolean excludedFromClaimAll(ProgressModel.Node n) {
        return n.rewards.stream().anyMatch(rw -> ProgressModel.boolOf(rw, "exclude_from_claim_all"));
    }

    // ------------------------------------------------------------------ sending

    static void event(ServerPlayer player, PlayerProgress p, String kind, String node) {
        p.event(kind, node, System.currentTimeMillis());
        ProgressStore.get(player.server).setDirty();
        PENDING.computeIfAbsent(player.getUUID(), k -> new ArrayList<>()).add(kind + "|" + node);
        markDirty(player);
    }

    static void markDirty(ServerPlayer player) {
        DIRTY.put(player.getUUID(), true);
    }

    static void markExperienceDirty(ServerPlayer player) {
        DIRTY.putIfAbsent(player.getUUID(), false);
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
    }

    public static void onLogout(ServerPlayer player) {
        PENDING.remove(player.getUUID());
        DIRTY.remove(player.getUUID());
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

    /** An item task's matcher as one string: an item id, or {@code #tag}. */
    static String itemSpec(JsonObject task) {
        JsonElement it = task.get("item");
        if (it == null) {
            return "";
        }
        if (it.isJsonPrimitive()) {
            return it.getAsString();
        }
        JsonObject o = it.getAsJsonObject();
        if (o.has("tag")) {
            return "#" + o.get("tag").getAsString();
        }
        return o.has("id") ? o.get("id").getAsString() : "";
    }

    static boolean itemMatches(String spec, ItemStack st) {
        if (spec.isEmpty()) {
            return false;
        }
        if (spec.startsWith("#")) {
            ResourceLocation id = ResourceLocation.tryParse(spec.substring(1));
            return id != null && st.is(TagKey.create(Registries.ITEM, id));
        }
        ResourceLocation id = ResourceLocation.tryParse(spec);
        if (id == null) {
            return false;
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        return st.is(item);
    }

    private static int countItems(ServerPlayer player, String spec) {
        int c = 0;
        var inv = player.getInventory();
        for (int s = 0; s < inv.getContainerSize(); s++) {
            ItemStack st = inv.getItem(s);
            if (!st.isEmpty() && itemMatches(spec, st)) {
                c += st.getCount();
            }
        }
        return c;
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
