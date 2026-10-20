package com.kuronami.questtree.server;

import com.kuronami.questtree.Constants;
import com.kuronami.questtree.data.ProgressModel;
import com.kuronami.questtree.progress.PlayerProgress;
import com.kuronami.questtree.progress.ProgressRules;
import com.kuronami.questtree.team.TeamResolver;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Admin commands on players' progress (what FTB Quests' {@code /ftbquests change_progress} and Pufferfish's Skills'
 * {@code /puffish_skills skills|points|experience} offer), also usable from command rewards and functions:
 * <pre>
 * /quest_tree reset &lt;players&gt; [chapter|node]      forget progress (all, one chapter, one node)
 * /quest_tree complete &lt;players&gt; &lt;node&gt;           finish a quest's tasks (rewards stay claimable)
 * /quest_tree respec &lt;players&gt; &lt;chapter&gt;          give back every point spent in a skill chapter
 * /quest_tree points &lt;players&gt; &lt;chapter&gt; &lt;n&gt;      add (or remove) spendable points
 * /quest_tree experience &lt;players&gt; &lt;chapter&gt; &lt;n&gt;  add experience to a skill chapter
 * /quest_tree lock|unlock &lt;players&gt; &lt;chapter&gt;    hide/show one chapter for those players
 * </pre>
 * Ids are {@code <namespace>:<chapter>} and {@code <namespace>:<chapter>/<node>}.
 */
final class PlayerCommands {

    private static final SuggestionProvider<CommandSourceStack> CHAPTERS = (c, b) ->
            SharedSuggestionProvider.suggest(ProgressService.rules().model().chapters.keySet(), b);
    private static final SuggestionProvider<CommandSourceStack> SKILL_CHAPTERS = (c, b) ->
            SharedSuggestionProvider.suggest(ProgressService.rules().model().chapters.values().stream()
                    .filter(ProgressModel.Chapter::isSkills).map(ch -> ch.id), b);
    private static final SuggestionProvider<CommandSourceStack> ANY = (c, b) -> SharedSuggestionProvider.suggest(
            java.util.stream.Stream.concat(ProgressService.rules().model().chapters.keySet().stream(),
                    ProgressService.rules().model().nodes.keySet().stream()), b);
    private static final SuggestionProvider<CommandSourceStack> NODES = (c, b) ->
            SharedSuggestionProvider.suggest(ProgressService.rules().model().nodes.keySet(), b);

    private PlayerCommands() {
    }

    static void register(CommandDispatcher<CommandSourceStack> d) {
        // the root is shared with ProgressCommands (party commands are open to everyone), so each admin literal carries its own gate
        d.register(Commands.literal(Constants.COMMAND)
                .then(Commands.literal("reset").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("players", EntityArgument.players())
                                .executes(c -> each(c, (p, pr) -> resetAll(p, pr), PlayerCommands::clearOwnClaims))
                                .then(Commands.argument("id", ResourceLocationArgument.id()).suggests(ANY)
                                        .executes(c -> each(c, (p, pr) -> reset(p, pr, id(c)),
                                        (p, pr) -> clearOwnClaims(p, pr, id(c)))))))
                .then(Commands.literal("complete").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("players", EntityArgument.players())
                                .then(Commands.argument("id", ResourceLocationArgument.id()).suggests(NODES)
                                        .executes(c -> each(c, (p, pr) -> complete(p, pr, id(c)))))))
                .then(Commands.literal("respec").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("players", EntityArgument.players())
                                .then(Commands.argument("id", ResourceLocationArgument.id()).suggests(SKILL_CHAPTERS)
                                        .executes(c -> each(c, (p, pr) -> respec(p, pr, id(c)))))))
                .then(Commands.literal("lock").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("players", EntityArgument.players())
                                .then(Commands.argument("id", ResourceLocationArgument.id()).suggests(CHAPTERS)
                                        .executes(c -> each(c, (p, pr) -> chapterOpen(c, p, pr, false))))))
                .then(Commands.literal("unlock").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("players", EntityArgument.players())
                                .then(Commands.argument("id", ResourceLocationArgument.id()).suggests(CHAPTERS)
                                        .executes(c -> each(c, (p, pr) -> chapterOpen(c, p, pr, true))))))
                .then(Commands.literal("points").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("players", EntityArgument.players())
                                .then(Commands.argument("id", ResourceLocationArgument.id()).suggests(SKILL_CHAPTERS)
                                        .then(Commands.argument("amount", IntegerArgumentType.integer())
                                                .executes(c -> each(c, (p, pr) -> pr.bonus.merge(ProgressRules.key(chapter(c)),
                                                        IntegerArgumentType.getInteger(c, "amount"), Integer::sum)))))))
                .then(Commands.literal("experience").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("players", EntityArgument.players())
                                .then(Commands.argument("id", ResourceLocationArgument.id()).suggests(SKILL_CHAPTERS)
                                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                                                .executes(c -> each(c, (p, pr) -> {
                                                    int levels = ProgressService.rules().addExperience(pr, chapter(c),
                                                            DoubleArgumentType.getDouble(c, "amount"));
                                                    if (levels > 0) {
                                                        ProgressService.event(p, pr, "level", chapter(c).id + "/");
                                                    }
                                                })))))));
    }

    private static String id(CommandContext<CommandSourceStack> c) {
        return ResourceLocationArgument.getId(c, "id").toString();
    }

    private static ProgressModel.Chapter chapter(CommandContext<CommandSourceStack> c) {
        ProgressModel.Chapter ch = ProgressService.rules().model().chapters.get(id(c));
        if (ch == null) {
            throw new IllegalArgumentException("Unknown chapter " + id(c));
        }
        return ch;
    }

    private static int each(CommandContext<CommandSourceStack> c, BiConsumer<ServerPlayer, PlayerProgress> change) throws CommandSyntaxException {
        return each(c, change, null);
    }

    /**
     * Runs the change once per team, then re-checks, saves and syncs to every online member. Selecting several players
     * of one team must not apply a shared change twice (a reset would revoke rewards twice, points would double).
     * {@code perMember} runs for every target first, for the parts that stay with one player (claims), so resetting a
     * whole party also clears each member's own claims.
     */
    private static int each(CommandContext<CommandSourceStack> c, BiConsumer<ServerPlayer, PlayerProgress> change,
                            BiConsumer<ServerPlayer, PlayerProgress> perMember) throws CommandSyntaxException {
        Collection<ServerPlayer> players = EntityArgument.getPlayers(c, "players");
        try {
            if (perMember != null) {
                for (ServerPlayer p : players) {
                    perMember.accept(p, ProgressService.progress(p));
                }
            }
            Map<UUID, ServerPlayer> firstOfTeam = new LinkedHashMap<>();
            for (ServerPlayer p : players) {
                firstOfTeam.putIfAbsent(TeamResolver.teamId(p.server, p.getUUID()), p);
            }
            for (ServerPlayer p : firstOfTeam.values()) {
                PlayerProgress pr = ProgressService.progress(p);
                change.accept(p, pr);
                ProgressStore.get(p.server).setDirty();
                ProgressService.recheck(p, pr);
                ProgressService.markDirty(p);
                ProgressService.flushTeam(p);
            }
        } catch (IllegalArgumentException ex) {
            c.getSource().sendFailure(Component.literal(ex.getMessage()));
            return 0;
        }
        int n = players.size();
        c.getSource().sendSuccess(() -> Component.translatableWithFallback("commands.kuronami_quest_tree.changed",
                "Progress changed for %s players", n), true);
        return n;
    }

    /** A full reset forgets the player's own claims and recent list before the shared record is reset. */
    private static void clearOwnClaims(ServerPlayer p, PlayerProgress pr) {
        pr.claimed.clear();
        pr.claimedRewards.clear();
        pr.recent.clear();
    }

    /** One chapter or node reset clears only the claims that belong to it, for every selected member. */
    private static void clearOwnClaims(ServerPlayer p, PlayerProgress pr, String id) {
        ProgressModel model = ProgressService.rules().model();
        if (model.chapters.containsKey(id)) {
            ProgressModel.Chapter ch = model.chapters.get(id);
            for (ProgressModel.Node n : ch.nodes.values()) {
                pr.claimed.remove(n.key);
                pr.claimedRewards.removeIf(k -> k.startsWith(n.key + "#"));
            }
        } else if (model.nodes.containsKey(id)) {
            ProgressModel.Node n = model.nodes.get(id);
            pr.claimed.remove(n.key);
            pr.claimedRewards.removeIf(k -> k.startsWith(n.key + "#"));
        }
    }

    private static void resetAll(ServerPlayer p, PlayerProgress pr) {
        for (ProgressModel.Chapter ch : ProgressService.rules().model().chapters.values()) {
            for (ProgressModel.Node n : ch.nodes.values()) {
                resetNode(p, pr, n);
            }
        }
        // a full reset forgets everything stored about skills, including the per-player chapter lock overrides
        ProgressRules.clearAll(pr);
    }

    /**
     * Pufferfish's Skills' category lock/unlock: a per-player override of {@code points.unlocked_by_default}.
     * Locking hides the chapter and takes back its rewards (attributes, scoreboard, command/tag unlocks) while keeping
     * the skills unlocked; unlocking puts back the rewards of every open chapter (2026-10-20 review).
     */
    private static void chapterOpen(CommandContext<CommandSourceStack> c, ServerPlayer p, PlayerProgress pr, boolean open) {
        ProgressModel.Chapter ch = chapter(c);
        pr.chapterOpen.put(ch.id, open);
        if (open) {
            Rewards.reapplyAttributes(p, pr);
        } else {
            Rewards.revokeChapter(p, pr, ch);
        }
    }

    private static void reset(ServerPlayer p, PlayerProgress pr, String id) {
        ProgressModel model = ProgressService.rules().model();
        if (model.chapters.containsKey(id)) {
            resetChapter(p, pr, model.chapters.get(id));
        } else if (model.nodes.containsKey(id)) {
            resetNode(p, pr, model.nodes.get(id));
        } else {
            throw new IllegalArgumentException("Unknown chapter or node " + id);
        }
    }

    static void resetChapter(ServerPlayer p, PlayerProgress pr, ProgressModel.Chapter ch) {
        for (ProgressModel.Node n : ch.nodes.values()) {
            resetNode(p, pr, n);
        }
        // resetNode already subtracted this chapter's share of a shared spent counter; the pool's experience, level
        // and extra points stay because they belong to every chapter in the pool (2026-10-20 review)
        ProgressRules.resetChapterData(pr, ch);
    }

    /**
     * Pufferfish's Skills' {@code erase_on_death}: a chapter without a pool starts over completely; a pooled chapter
     * erases its whole pool and revokes the skills of every chapter in it, and each erased chapter also forgets its
     * per-player lock override (2026-10-20 decision; Pufferfish's Skills has no pool equivalent).
     */
    static void eraseOnDeath(ServerPlayer p, PlayerProgress pr, ProgressModel.Chapter ch) {
        if (!ProgressRules.isPooled(ch)) {
            resetChapter(p, pr, ch);
            ProgressRules.eraseChapterData(pr, ProgressService.rules().model(), ch);
            return;
        }
        String pool = ProgressRules.key(ch);
        for (ProgressModel.Chapter other : ProgressService.rules().model().chapters.values()) {
            if (pool.equals(ProgressRules.key(other))) {
                resetChapter(p, pr, other);
            }
        }
        ProgressRules.eraseChapterData(pr, ProgressService.rules().model(), ch);
    }

    private static void resetNode(ServerPlayer p, PlayerProgress pr, ProgressModel.Node n) {
        // take the node out before revoking: the scoreboard reward reads how many of its definition are still taken
        Integer taken = pr.taken.remove(n.key);
        if (taken != null && taken > 0) {
            Rewards.revokeSkill(p, pr, n, taken);
        } else {
            Rewards.removeAttributes(p, n);
        }
        if (taken != null && n.chapter.isSkills()) {
            // give the points back, as a chapter reset or respec would
            pr.spent.computeIfPresent(ProgressRules.key(n.chapter), (k, v) -> Math.max(0, v - Math.max(0, n.cost) * taken));
        }
        pr.clearNode(n.key);
        pr.repeats.remove(n.key);
        pr.cooldown.remove(n.key);
    }

    private static void complete(ServerPlayer p, PlayerProgress pr, String id) {
        ProgressModel.Node n = ProgressService.rules().model().nodes.get(id);
        if (n == null) {
            throw new IllegalArgumentException("Unknown node " + id);
        }
        for (int i = 0; i < n.tasks.size(); i++) {
            pr.setTask(n.key, i, com.kuronami.questtree.progress.ProgressRules.target(n.tasks.get(i)));
        }
        if (n.tasks.isEmpty() && !n.chapter.isSkills() && !pr.completed.contains(n.key)) {
            // task-less quests: complete with their automatic rewards, like a normal completion
            pr.completed.add(n.key);
            ProgressService.event(p, pr, "complete", n.key);
            ProgressService.autoRewards(p, pr, n);
        }
    }

    /** Refunds a skill chapter: every taken node is given back with its attribute bonuses; experience and level stay. */
    private static void respec(ServerPlayer p, PlayerProgress pr, String id) {
        ProgressModel.Chapter ch = ProgressService.rules().model().chapters.get(id);
        if (ch == null || !ch.isSkills()) {
            throw new IllegalArgumentException("Unknown skill chapter " + id);
        }
        // refund before the nodes are removed: the amount is the chapter's taken nodes (2026-10-20 review)
        ProgressRules.refundChapterSpent(pr, ch);
        for (ProgressModel.Node n : ch.nodes.values()) {
            Integer taken = pr.taken.remove(n.key);
            if (taken != null) {
                Rewards.revokeSkill(p, pr, n, taken);
            }
        }
    }
}
