package com.kuronami.questtree.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kuronami.questtree.Constants;
import com.kuronami.questtree.data.ProgressModel;
import com.kuronami.questtree.progress.PlayerProgress;
import com.kuronami.questtree.progress.ProgressRules;
import com.kuronami.questtree.progress.SkillRewardPolicy;
import com.kuronami.questtree.team.TeamResolver;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Hands out rewards (DATA_MODEL.md "報酬"). Every reward is checked again here; nothing trusts the client. */
public final class Rewards {

    private Rewards() {
    }

    /** Hands out one reward. False when it could not be paid (a missing provider or a failed payment). */
    static boolean give(ServerPlayer player, PlayerProgress p, ProgressModel.Node n, JsonObject rw, int choice,
                        String rollKey) {
        if (!ProgressService.conditionsMet(player, p, rw) || blocked(p, rw)) {
            return false;
        }
        try {
            return switch (ProgressService.type(rw)) {
                case "currency" -> currency(player, rw);
                case "dummy" -> {
                    // FTBQ's dummy reward: it exists to make a quest show a reward icon and does nothing
                    yield true;
                }
                case "item" -> { item(player, rw); yield true; }
                case "xp" -> { player.giveExperiencePoints(intOf(rw, "amount", 0)); yield true; }
                case "xp_levels" -> { player.giveExperienceLevels(intOf(rw, "amount", 0)); yield true; }
                case "command" -> { command(player, rw); yield true; }
                case "table" -> table(player, p, n, rw, choice, rollKey);
                case "advancement" -> { advancement(player, rw); yield true; }
                case "stage" -> { stage(player, p, rw); yield true; }
                case "attribute" -> { attribute(player, n.key, slotOf(n, rw), rw); yield true; }
                case "points" -> { p.bonus.merge(pointsChapter(n, rw), ProgressRules.pointsAmount(rw), Integer::sum); yield true; }
                case "tag" -> { player.addTag(ProgressService.str(rw, "tag")); yield true; }
                case "toast" -> { ProgressService.event(player, p, "toast", n.key); yield true; }
                case "scoreboard" -> { scoreboard(player, p, n, rw); yield true; }
                // FTB Quests' custom reward: its effect is addon Java code; hand the claim to QuestEvents and do nothing else
                case "custom" -> { QuestEvents.fire(player, n, rw, false, -1); yield true; }
                // Kuronami's Trophies (mod-108): it gives each trophy once per player and engraves it; no dependency, only its command
                case "trophy" -> {
                    // only a valid id reaches the command line
                    if (com.kuronami.questtree.platform.Services.PLATFORM.isModLoaded("kuronami_trophies")
                            && ResourceLocation.tryParse(ProgressService.str(rw, "trophy")) != null && ProgressService.str(rw, "trophy").contains(":")) {
                        ServerCommands.run(player, "trophy award @s " + ProgressService.str(rw, "trophy"), 2, true);
                    }
                    yield true;
                }
                default -> {
                    Constants.LOG.warn("Reward type '{}' of {} is not supported yet", ProgressService.type(rw), n.key);
                    yield true;
                }
            };
        } catch (RuntimeException ex) {
            Constants.LOG.error("Reward of {} failed: {}", n.key, ex.toString());
            return false;
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean CURRENCY_WARNED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Pays through FTB Library's currency hook, which every economy mod of that family registers with. Without an
     * economy mod the reward is taken and pays nothing (the log says why once); only a provider that fails keeps the
     * reward unclaimed, so the player can try again (2026-10-21 review).
     */
    private static boolean currency(ServerPlayer player, JsonObject rw) {
        int amount = intOf(rw, "amount", 1);
        if (amount <= 0) {
            return true;
        }
        boolean provider = com.kuronami.questtree.platform.Services.PLATFORM.isModLoaded("ftblibrary")
                && FtbLibraryCurrencyBridge.available();
        boolean paid = provider && FtbLibraryCurrencyBridge.give(player, amount);
        var outcome = com.kuronami.questtree.progress.CurrencyClaim.resolve(provider, paid);
        if (outcome == com.kuronami.questtree.progress.CurrencyClaim.Outcome.NO_PROVIDER) {
            if (CURRENCY_WARNED.compareAndSet(false, true)) {
                Constants.LOG.warn("A currency reward paid nothing: no economy mod registered with FTB Library");
            }
            return true;
        }
        if (outcome == com.kuronami.questtree.progress.CurrencyClaim.Outcome.PAID) {
            return true;
        }
        Constants.LOG.warn("A currency reward of {} failed to pay", amount);
        // the claim stays open, so the player can try again once the economy mod is back
        player.sendSystemMessage(net.minecraft.network.chat.Component.translatableWithFallback(
                "message.kuronami_quest_tree.currency_failed",
                "The currency reward could not be paid; it stays unclaimed."));
        return false;
    }

    /**
     * An admin can stop a player from taking rewards ({@code /quest_tree rewards block}); tasks still count. A reward
     * with {@code "ignore_reward_blocking"} (or {@code "ignore_blocking"}) is exempt, so a pack can keep a story item
     * or a command flowing while the player is being held.
     */
    static boolean blocked(PlayerProgress p, JsonObject rw) {
        return p.rewardsBlocked() && !ProgressModel.boolOf(rw, "ignore_reward_blocking")
                && !ProgressModel.boolOf(rw, "ignore_blocking");
    }

    /**
     * A stage reward. With {@code "team": true} the stage belongs to the team record and is mirrored into Pack Stages
     * for every online member, so content outside the quest tree can keep using its command.
     */
    private static void stage(ServerPlayer player, PlayerProgress p, JsonObject rw) {
        String stage = ProgressService.str(rw, "stage");
        boolean add = !ProgressModel.boolOf(rw, "remove");
        if (!ProgressModel.boolOf(rw, "team")) {
            if (add) {
                p.stages.add(stage);
            } else {
                p.stages.remove(stage);
            }
            ServerCommands.run(player, "pack_stages " + (add ? "add " : "remove ")
                    + player.getGameProfile().getName() + " " + stage, 2, true);
            return;
        }
        if (add) {
            p.teamStages.add(stage);
        } else {
            p.teamStages.remove(stage);
        }
        for (ServerPlayer member : TeamResolver.onlineMembers(player.server, player.getUUID())) {
            ServerCommands.run(member, "pack_stages " + (add ? "add " : "remove ")
                    + member.getGameProfile().getName() + " " + stage, 2, true);
            PlayerProgress mp = member.getUUID().equals(player.getUUID()) ? p : ProgressService.progress(member);
            if (add) {
                mp.teamStagesApplied.add(stage);
            } else {
                mp.teamStagesApplied.remove(stage);
            }
        }
    }

    /** Pays rewards that were taken while an admin had blocked pay-outs. Called when the block is lifted. */
    static void payPending(ServerPlayer player, PlayerProgress p) {
        var model = ProgressService.rules().model();
        if (!com.kuronami.questtree.progress.RewardLedger.canResolve(model)) {
            // an empty model must not read as "every node was removed" and forget the debt
            return;
        }
        boolean changed = false;
        for (String key : new ArrayList<>(p.pendingRewards.keySet())) {
            String node = com.kuronami.questtree.progress.RewardLedger.pendingNode(key);
            int rank = com.kuronami.questtree.progress.RewardLedger.pendingRank(key);
            int index = com.kuronami.questtree.progress.RewardLedger.pendingIndex(key);
            ProgressModel.Node n = model.nodes.get(node);
            if (n == null) {
                p.pendingRewards.remove(key);
                changed = true;
                continue;
            }
            List<JsonObject> list = rank >= 0 ? rewardsOfRank(n, rank) : n.rewards;
            if (index < 0 || index >= list.size()) {
                p.pendingRewards.remove(key);
                changed = true;
                continue;
            }
            JsonObject rw = list.get(index);
            if (blocked(p, rw) || !ProgressService.conditionsMet(player, p, rw)) {
                continue;
            }
            int owed = p.pendingRewards.getOrDefault(key, 0);
            for (int paid = 0; paid < owed; paid++) {
                if (!give(player, p, n, rw, -1, key)) {
                    break;
                }
                if (owed - paid - 1 <= 0) {
                    p.pendingRewards.remove(key);
                } else {
                    p.pendingRewards.put(key, owed - paid - 1);
                }
                changed = true;
            }
        }
        if (changed) {
            ProgressStore.get(player.server).setDirty();
        }
    }

    /**
     * Re-applies shared team stages after a login, for members who were offline when the stage was granted, and
     * removes the ones this player was given while the team no longer holds them (2026-10-21 review).
     */
    static void applyTeamStages(ServerPlayer player, PlayerProgress p) {
        var plan = com.kuronami.questtree.progress.StageSync.plan(p.teamStagesApplied, p.teamStages);
        Set<String> removable = new java.util.HashSet<>(plan.remove());
        // a stage this player was granted directly is in p.stages as well; losing the team's copy must not take it
        removable.removeAll(p.stages);
        for (String stage : removable) {
            ServerCommands.run(player, "pack_stages remove " + player.getGameProfile().getName() + " " + stage, 2, true);
        }
        for (String stage : plan.add()) {
            ServerCommands.run(player, "pack_stages add " + player.getGameProfile().getName() + " " + stage, 2, true);
        }
        p.teamStagesApplied.clear();
        p.teamStagesApplied.addAll(p.teamStages);
        for (String stage : p.stages) {
            ServerCommands.run(player, "pack_stages add " + player.getGameProfile().getName() + " " + stage, 2, true);
        }
    }

    /** Rewards of a skill node when it is taken: the node's own rewards, or the rewards of rank {@code rank}. */
    static void giveSkill(ServerPlayer player, PlayerProgress p, ProgressModel.Node n, int rank) {
        // A respec keeps the marks, so the one-off rewards of a rank cannot be farmed by taking it again. They are
        // recorded per reward once it was actually paid or moved to pending (2026-10-22 review).
        giveSkillOne(player, p, n, rank, true);
        // the skill belongs to the team: its per-player effects (attributes, commands, tags) reach every online member.
        // Shared values (points) and one-off pay-outs are applied once, by the player who took it
        for (ServerPlayer member : members(player)) {
            if (!member.getUUID().equals(player.getUUID())) {
                // a member's own pay-out (a blocked command or tag) is owed to that member, not to the taker, and
                // each member's own marks decide whether that member already had it
                giveSkillOne(member, ProgressService.progress(member), n, rank, false);
            }
        }
    }

    /** Rewards that must not be paid twice for the same rank, even after a respec (DATA_MODEL "スキルの振り直し"). */
    private static List<ServerPlayer> members(ServerPlayer player) {
        return new ArrayList<>(com.kuronami.questtree.team.TeamResolver.onlineMembers(player.server, player.getUUID()));
    }

    private static void giveSkillOne(ServerPlayer player, PlayerProgress p, ProgressModel.Node n, int rank,
                                     boolean full) {
        List<JsonObject> list = rewardsOfRank(n, rank);
        for (int index = 0; index < list.size(); index++) {
            JsonObject rw = list.get(index);
            if (!ProgressService.conditionsMet(player, p, rw)) {
                continue;
            }
            boolean firstTake = SkillRewardPolicy.owed(p, n, rank, index);
            String rollKey = com.kuronami.questtree.progress.RewardLedger.pendingKey(n.key, rank, index);
            String type = ProgressService.type(rw);
            if (type.equals("attribute")) {
                attribute(player, n.key, rank * 100 + index, rw);
            } else if (full) {
                if (SkillRewardPolicy.pays(rw, firstTake)) {
                    boolean paid = com.kuronami.questtree.progress.RewardLedger.claim(p, rw, n.key, index, rank,
                            false, blocked(p, rw), () -> give(player, p, n, rw, -1, rollKey));
                    SkillRewardPolicy.recordPaid(p, n, rank, index, paid);
                }
            } else if ((type.equals("command") && (firstTake || SkillRewardPolicy.hasLockCommand(rw)))
                    || type.equals("tag")) {
                boolean paid;
                if (blocked(p, rw)) {
                    // a member's own pay-out is owed to them, not paid and forgotten
                    paid = com.kuronami.questtree.progress.RewardLedger.claim(p, rw, n.key, index, rank, false,
                            true, () -> true);
                } else {
                    paid = give(player, p, n, rw, -1, rollKey);
                }
                SkillRewardPolicy.recordPaid(p, n, rank, index, paid);
            }
        }
    }

    /** Index of a quest reward, so two attribute rewards of one quest get separate modifiers (1000+ keeps clear of skill ranks). */
    private static int slotOf(ProgressModel.Node n, JsonObject rw) {
        for (int i = 0; i < n.rewards.size(); i++) {
            if (n.rewards.get(i) == rw) {
                return 1000 + i;
            }
        }
        return 1000;
    }

    /** Number of entries of the table a table reward points at (0 when missing). */
    static int tableSize(JsonObject rw) {
        JsonObject table = ProgressService.rules().model().rewardTables.get(ProgressService.str(rw, "table"));
        return table == null || !table.has("entries") ? 0 : table.getAsJsonArray("entries").size();
    }

    /**
     * Puts back every attribute modifier the player earned: taken skills (each rank so far) and claimed quests. Used on
     * join and respawn, because respawning copies only base values. Fixed modifier ids make repeating it harmless.
     */
    /**
     * Recomputes the attribute modifiers of every online member of the player's team from the team's current skills:
     * all of this mod's modifiers are removed first, so none outlive a respec, reset, lock or leaving the team.
     */
    static void refreshTeamAttributes(ServerPlayer player) {
        for (ServerPlayer member : members(player)) {
            refreshAttributes(member, ProgressService.progress(member));
        }
    }

    static void refreshAttributes(ServerPlayer player, PlayerProgress p) {
        clearAttributes(player);
        reapplyAttributes(player, p);
    }

    /** Removes every attribute modifier this mod added, whatever node it came from. */
    static void clearAttributes(ServerPlayer player) {
        for (Holder<Attribute> attr : BuiltInRegistries.ATTRIBUTE.holders().toList()) {
            AttributeInstance inst = player.getAttribute(attr);
            if (inst == null) {
                continue;
            }
            for (AttributeModifier m : new ArrayList<>(inst.getModifiers())) {
                if (Constants.MOD_ID.equals(m.id().getNamespace())) {
                    inst.removeModifier(m.id());
                }
            }
        }
    }

    static void reapplyAttributes(ServerPlayer player, PlayerProgress p) {
        var model = ProgressService.rules().model();
        for (var e : p.taken.entrySet()) {
            ProgressModel.Node n = model.nodes.get(e.getKey());
            // a locked chapter's rewards were taken back by the lock; only open chapters put them back (PS showCategory)
            if (n != null && ProgressRules.chapterUnlocked(p, n.chapter)) {
                for (int rank = 0; rank < e.getValue(); rank++) {
                    List<JsonObject> list = rewardsOfRank(n, rank);
                    // a reward whose conditions did not hold was never handed out, so do not put it back either
                    for (int i : SkillRewardPolicy.grantedIndexes(n, rank,
                            rw -> ProgressService.conditionsMet(player, p, rw))) {
                        if (ProgressService.type(list.get(i)).equals("attribute")) {
                            attribute(player, n.key, rank * 100 + i, list.get(i));
                        }
                    }
                }
                updateScoreboard(player, p, n, e.getValue());
            }
        }
        // claimed quests, and repeatable quests claimed before (a repeat clears "claimed" and the per-reward marks).
        // Rewards claimed one by one (2026-10-20) put back only the attribute rewards that were actually taken.
        java.util.Set<String> claimedOnce = new java.util.HashSet<>(p.claimed);
        p.repeats.forEach((k, v) -> {
            if (v > 0) {
                claimedOnce.add(k);
            }
        });
        for (String key : claimedOnce) {
            ProgressModel.Node n = model.nodes.get(key);
            if (n != null && ProgressRules.chapterUnlocked(p, n.chapter)) {
                for (JsonObject rw : n.rewards) {
                    if (ProgressService.type(rw).equals("attribute")
                            && ProgressService.conditionsMet(player, p, rw)) {
                        attribute(player, n.key, slotOf(n, rw), rw);
                    }
                }
            }
        }
        java.util.Set<String> marks = new java.util.HashSet<>(p.claimedRewards);
        marks.addAll(p.teamClaimedRewards);
        for (String mark : marks) {
            int hash = mark.lastIndexOf('#');
            if (hash <= 0) {
                continue;
            }
            ProgressModel.Node n = model.nodes.get(mark.substring(0, hash));
            if (n == null || claimedOnce.contains(n.key)) {
                // a wholly claimed node already put every attribute back above
                continue;
            }
            int index;
            try {
                index = Integer.parseInt(mark.substring(hash + 1));
            } catch (NumberFormatException ex) {
                continue;
            }
            if (n != null && index >= 0 && index < n.rewards.size()) {
                JsonObject rw = n.rewards.get(index);
                if (ProgressService.type(rw).equals("attribute")
                        && ProgressService.conditionsMet(player, p, rw)) {
                    attribute(player, n.key, slotOf(n, rw), rw);
                }
            }
        }
    }

    /** Removes the attribute modifiers a node gave (all ranks, quest rewards), for resets and respec. */
    /**
     * Takes a skill back ({@code taken} ranks of it): attribute bonuses go, each command reward runs its
     * {@code lock_command}, and tag rewards are removed, as Pufferfish's Skills does on a respec.
     */
    static void revokeSkill(ServerPlayer player, PlayerProgress p, ProgressModel.Node n, int taken) {
        // points are shared, and giveSkill paid them only for the taker: take them back once, before the member
        // loop can run the per-player effects (a respec before this point is refused when they were already spent).
        // A points reward still pending was never paid, so its debt is cancelled instead (2026-10-22 review).
        Map<String, PlayerProgress> owners = new HashMap<>();
        Map<String, Integer> pending = new HashMap<>();
        for (ServerPlayer member : members(player)) {
            PlayerProgress mp = member.getUUID().equals(player.getUUID()) ? p : ProgressService.progress(member);
            mp.pendingRewards.forEach((key, owed) -> {
                pending.merge(key, owed, Integer::sum);
                owners.putIfAbsent(key, mp);
            });
        }
        Map<String, Integer> before = new HashMap<>(p.bonus);
        ProgressService.rules().revokePoints(p, n, taken, pending);
        // write the cancellations back into the records that owed them
        for (Map.Entry<String, PlayerProgress> e : owners.entrySet()) {
            int left = pending.getOrDefault(e.getKey(), 0);
            if (left > 0) {
                e.getValue().pendingRewards.put(e.getKey(), left);
            } else {
                e.getValue().pendingRewards.remove(e.getKey());
            }
        }
        // an admin respec/reset does not go through the screen's affordability check, so an overdraft must at least
        // leave a trace instead of quietly showing 0 points (2026-10-22 review)
        for (Map.Entry<String, Integer> e : p.bonus.entrySet()) {
            if (e.getValue() < 0 && !e.getValue().equals(before.get(e.getKey()))) {
                Constants.LOG.warn("Points bonus of {} went negative ({} = {}) after taking back {}",
                        player.getGameProfile().getName(), e.getKey(), e.getValue(), n.key);
            }
        }
        for (ServerPlayer member : members(player)) {
            PlayerProgress mp = member.getUUID().equals(player.getUUID()) ? p : ProgressService.progress(member);
            revokeSkillOne(member, mp, n, taken);
        }
    }

    private static void revokeSkillOne(ServerPlayer player, PlayerProgress p, ProgressModel.Node n, int taken) {
        removeAttributesOne(player, n);
        int ranks = Math.max(1, Math.min(taken, Math.max(1, n.ranks)));
        for (int rank = 0; rank < ranks; rank++) {
            List<JsonObject> list = rewardsOfRank(n, rank);
            for (int index = 0; index < list.size(); index++) {
                JsonObject rw = list.get(index);
                if (ProgressService.type(rw).equals("points")) {
                    // revokePoints took the shared points back once for the team and cancelled a pending copy there
                    continue;
                }
                if (SkillRewardPolicy.cancelPending(p, n, rank, index)) {
                    // the pay-out was still owed, not made: the debt goes with the skill and nothing is undone
                    continue;
                }
                if (!ProgressService.conditionsMet(player, p, rw)) {
                    // the conditions did not hold when it was taken, so it was never handed out: nothing to undo
                    continue;
                }
                try {
                    switch (ProgressService.type(rw)) {
                        case "command" -> run(player, rw, ProgressService.str(rw, "lock_command"));
                        case "tag" -> player.removeTag(ProgressService.str(rw, "tag"));
                        default -> { }
                    }
                } catch (RuntimeException ex) {
                    Constants.LOG.error("Taking back a reward of {} failed: {}", n.key, ex.toString());
                }
            }
        }
        // the score is the number of still-unlocked instances of the reward's definition (PS RewardUpdateContext)
        updateScoreboard(player, p, n, taken);
    }

    /**
     * Pufferfish's Skills' {@code lockCategory}: the skills stay unlocked, but their rewards are taken back and every
     * scoreboard reward goes to zero on the hidden category. {@code unlockCategory} later re-applies them through
     * {@link #reapplyAttributes}.
     */
    static void revokeChapter(ServerPlayer player, PlayerProgress p, ProgressModel.Chapter c) {
        for (ServerPlayer member : members(player)) {
            // each member's own claims and pending rewards decide what that member had and is still owed
            PlayerProgress mp = member.getUUID().equals(player.getUUID()) ? p : ProgressService.progress(member);
            revokeChapterOne(member, mp, c);
        }
    }

    private static void revokeChapterOne(ServerPlayer player, PlayerProgress p, ProgressModel.Chapter c) {
        for (ProgressModel.Node n : c.nodes.values()) {
            int taken = p.taken.getOrDefault(n.key, 0);
            if (taken > 0) {
                revokeSkillOne(player, p, n, taken);
            } else if (p.claimed.contains(n.key)) {
                removeAttributesOne(player, n);
            }
            // a locked chapter shows no score, whatever its skills still are
            for (JsonObject rw : n.rewards) {
                if (ProgressService.type(rw).equals("scoreboard")) {
                    setScoreboard(player, rw, 0);
                }
            }
        }
    }

    static void removeAttributes(ServerPlayer player, ProgressModel.Node n) {
        for (ServerPlayer member : members(player)) {
            removeAttributesOne(member, n);
        }
    }

    private static void removeAttributesOne(ServerPlayer player, ProgressModel.Node n) {
        int ranks = Math.max(1, n.ranks);
        for (int rank = 0; rank < ranks; rank++) {
            List<JsonObject> list = rewardsOfRank(n, rank);
            for (int i = 0; i < list.size(); i++) {
                removeAttribute(player, list.get(i), modifierId(n.key, rank * 100 + i));
            }
        }
        for (int i = 0; i < n.rewards.size(); i++) {
            removeAttribute(player, n.rewards.get(i), modifierId(n.key, 1000 + i));
        }
    }

    private static void removeAttribute(ServerPlayer player, JsonObject rw, ResourceLocation id) {
        if (!ProgressService.type(rw).equals("attribute")) {
            return;
        }
        ResourceLocation rl = ResourceLocation.tryParse(ProgressService.str(rw, "attribute"));
        Holder<Attribute> attr = rl == null ? null : BuiltInRegistries.ATTRIBUTE.getHolder(rl).orElse(null);
        AttributeInstance inst = attr == null ? null : player.getAttribute(attr);
        if (inst != null) {
            inst.removeModifier(id);
        }
    }

    private static List<JsonObject> rewardsOfRank(ProgressModel.Node n, int rank) {
        if (n.ranks > 0 && n.json.has("ranks")) {
            JsonElement r = n.json.getAsJsonArray("ranks").get(Math.min(rank, n.ranks - 1));
            List<JsonObject> list = new ArrayList<>();
            if (r.isJsonObject() && r.getAsJsonObject().has("rewards")) {
                for (JsonElement e : r.getAsJsonObject().getAsJsonArray("rewards")) {
                    list.add(e.getAsJsonObject());
                }
            }
            return list;
        }
        return n.rewards;
    }

    private static void item(ServerPlayer player, JsonObject rw) {
        JsonElement it = rw.get("item");
        String id;
        int count = 1;
        if (it == null) {
            return;
        }
        if (it.isJsonPrimitive()) {
            id = it.getAsString();
        } else {
            JsonObject o = it.getAsJsonObject();
            id = o.has("id") ? o.get("id").getAsString() : "";
            count = intOf(o, "count", 1);
        }
        int bonus = intOf(rw, "random_bonus", 0);
        if (bonus > 0) {
            count += player.getRandom().nextInt(bonus + 1);
        }
        ResourceLocation rl = ResourceLocation.tryParse(id);
        Item item = rl == null ? Items.AIR : BuiltInRegistries.ITEM.get(rl);
        if (item == Items.AIR) {
            Constants.LOG.warn("Reward item '{}' does not exist", id);
            return;
        }
        int max = new ItemStack(item).getMaxStackSize();
        while (count > 0) {
            ItemStack st = new ItemStack(item, Math.min(count, max));
            count -= st.getCount();
            if (!player.getInventory().add(st) || !st.isEmpty()) {
                player.drop(st, false);
            }
        }
    }

    private static void command(ServerPlayer player, JsonObject rw) {
        // Pufferfish's Skills keeps the command of a skill reward as "unlock_command" (and its undo as "lock_command")
        String cmd = rw.has("command") ? ProgressService.str(rw, "command") : ProgressService.str(rw, "unlock_command");
        run(player, rw, cmd);
    }

    private static void run(ServerPlayer player, JsonObject rw, String cmd) {
        if (cmd.isEmpty()) {
            return;
        }
        // FTB Quests writes the player as {p}; Pufferfish's Skills commands run as the player (@s)
        cmd = cmd.replace("{p}", player.getGameProfile().getName());
        // the data pack author decides what runs, as with FTB Quests and Pufferfish's Skills; level 2 unless the data says otherwise
        int level = intOf(rw, "permission_level", 2);
        ServerCommands.run(player, cmd, level, !rw.has("silent") || ProgressModel.boolOf(rw, "silent"));
    }

    /**
     * A reward table. One with {@code loot_crate} metadata is FTB Quests' crate: opening a crate rolls the same table
     * once, so the roll result is granted here at claim time (2026-10-20). KQT has no crate item to carry and open
     * later, so the contents arrive directly instead of the box.
     */
    private static boolean table(ServerPlayer player, PlayerProgress p, ProgressModel.Node n, JsonObject rw,
                                 int choice, String rollKey) {
        JsonObject table = ProgressService.rules().model().rewardTables.get(ProgressService.str(rw, "table"));
        if (table == null || !table.has("entries")) {
            Constants.LOG.warn("Reward table '{}' of {} does not exist", ProgressService.str(rw, "table"), n.key);
            return true;
        }
        List<JsonObject> entries = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        for (JsonElement e : table.getAsJsonArray("entries")) {
            JsonObject o = e.getAsJsonObject();
            entries.add(o.getAsJsonObject("reward"));
            weights.add(o.has("weight") ? o.get("weight").getAsDouble() : 1.0);
        }
        String key = rollKey == null || rollKey.isEmpty() ? n.key : rollKey;
        String mode = rw.has("mode") ? rw.get("mode").getAsString() : "random";
        switch (mode) {
            case "all" -> {
                // every entry is paid once; a retry after a failure skips the entries already paid
                List<Integer> all = new ArrayList<>();
                for (int i = 0; i < entries.size(); i++) {
                    all.add(i);
                }
                return com.kuronami.questtree.progress.RewardPayout.pay(p, key + ">all", all,
                        (index, position) -> give(player, p, n, entries.get(index), -1, key + ">all>" + position));
            }
            case "choice" -> {
                if (choice < 0 || choice >= entries.size()) {
                    return false;
                }
                return give(player, p, n, entries.get(choice), -1, key + ">choice");
            }
            default -> {
                // FTB Quests: a random reward never rolls "empty" (only a loot crate's table uses empty_weight), and
                // entries with weight 0 are always granted outside the rolls (2026-10-20 review)
                boolean loot = mode.equals("loot");
                double empty = loot && table.has("empty_weight") ? table.get("empty_weight").getAsDouble() : 0;
                String picksKey = key + ">rolls";
                List<Integer> picks = com.kuronami.questtree.progress.RewardRoll.decode(p.rewardRolls.get(picksKey));
                if (picks == null) {
                    picks = com.kuronami.questtree.progress.RewardRoll.roll(weights, empty, loot,
                            intOf(table, "rolls", 1), () -> player.getRandom().nextDouble());
                    // decide and record before paying, so a failed entry does not make the next attempt roll again
                    p.rewardRolls.put(picksKey, com.kuronami.questtree.progress.RewardRoll.encode(picks));
                }
                boolean paid = com.kuronami.questtree.progress.RewardPayout.pay(p, key, picks,
                        (index, position) -> index < 0 || index >= entries.size()
                                || give(player, p, n, entries.get(index), -1, key + ">" + position));
                if (paid) {
                    p.rewardRolls.remove(picksKey);
                }
                return paid;
            }
        }
    }

    private static void advancement(ServerPlayer player, JsonObject rw) {
        ResourceLocation rl = ResourceLocation.tryParse(ProgressService.str(rw, "advancement"));
        AdvancementHolder h = rl == null ? null : player.server.getAdvancements().get(rl);
        if (h == null) {
            return;
        }
        String crit = ProgressService.str(rw, "criterion");
        AdvancementProgress prog = player.getAdvancements().getOrStartProgress(h);
        if (!crit.isEmpty()) {
            player.getAdvancements().award(h, crit);
            return;
        }
        for (String c : prog.getRemainingCriteria()) {
            player.getAdvancements().award(h, c);
        }
    }

    /**
     * Permanent attribute modifier with a fixed id per node and slot, so taking the same skill twice (or reloading)
     * replaces instead of stacking.
     */
    static void attribute(ServerPlayer player, String nodeKey, int slot, JsonObject rw) {
        ResourceLocation rl = ResourceLocation.tryParse(ProgressService.str(rw, "attribute"));
        Holder<Attribute> attr = rl == null ? null : BuiltInRegistries.ATTRIBUTE.getHolder(rl).orElse(null);
        if (attr == null) {
            Constants.LOG.warn("Attribute '{}' does not exist", ProgressService.str(rw, "attribute"));
            return;
        }
        AttributeInstance inst = player.getAttribute(attr);
        if (inst == null) {
            return;
        }
        ResourceLocation id = modifierId(nodeKey, slot);
        double value = rw.has("value") ? rw.get("value").getAsDouble() : 0;
        inst.removeModifier(id);
        inst.addPermanentModifier(new AttributeModifier(id, value, operation(ProgressService.str(rw, "operation"))));
    }

    static ResourceLocation modifierId(String nodeKey, int slot) {
        String path = (nodeKey.replace(':', '/') + "/" + slot).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]", "_");
        return ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, path);
    }

    private static AttributeModifier.Operation operation(String s) {
        return switch (s) {
            case "multiply_base", "addition_multiplied_base", "add_multiplied_base" -> AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
            case "multiply_total", "add_multiplied_total" -> AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
            default -> AttributeModifier.Operation.ADD_VALUE;
        };
    }

    /**
     * Pufferfish's Skills' scoreboard reward: set the player's score (not add to it), so claiming twice gives the same
     * number. {@code objective} is the current key; {@code scoreboard} is the name books written before it used.
     */
    private static void scoreboard(ServerPlayer player, PlayerProgress p, ProgressModel.Node n, JsonObject rw) {
        if (n.chapter.isSkills()) {
            // Pufferfish's Skills' ScoreboardReward sets the count of unlocked instances of the definition
            setScoreboard(player, rw, ProgressRules.definitionCount(p, n));
        } else {
            setScoreboard(player, rw, intOf(rw, "value", intOf(rw, "amount", 0)));
        }
    }

    private static void setScoreboard(ServerPlayer player, JsonObject rw, int value) {
        String name = ProgressService.str(rw, "objective");
        if (name.isEmpty()) {
            name = ProgressService.str(rw, "scoreboard");
        }
        if (name.isEmpty()) {
            return;
        }
        var objective = player.getScoreboard().getObjective(name);
        if (objective != null) {
            player.getScoreboard().getOrCreatePlayerScore(player, objective).set(value);
        }
    }

    /** Puts the scoreboard rewards of the ranks this player took back to their definition count. */
    private static void updateScoreboard(ServerPlayer player, PlayerProgress p, ProgressModel.Node n, int taken) {
        if (!n.chapter.isSkills() || taken <= 0) {
            return;
        }
        int count = ProgressRules.definitionCount(p, n);
        int ranks = Math.max(1, Math.min(taken, Math.max(1, n.ranks)));
        for (int rank = 0; rank < ranks; rank++) {
            for (JsonObject rw : rewardsOfRank(n, rank)) {
                if (ProgressService.type(rw).equals("scoreboard")) {
                    setScoreboard(player, rw, count);
                }
            }
        }
    }

    private static String pointsChapter(ProgressModel.Node n, JsonObject rw) {
        String c = ProgressService.str(rw, "chapter");
        if (c.isEmpty()) {
            return ProgressRules.key(n.chapter);
        }
        ProgressModel.Chapter target = ProgressService.rules().model().chapters.get(c);
        return target == null ? c : ProgressRules.key(target);
    }

    private static int intOf(JsonObject o, String k, int def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? (int) o.get(k).getAsDouble() : def;
    }
}
