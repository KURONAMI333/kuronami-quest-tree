package com.kuronami.questtree.server;

import com.kuronami.questtree.Constants;
import com.kuronami.questtree.data.ProgressModel;
import com.kuronami.questtree.progress.PlayerProgress;
import com.kuronami.questtree.progress.ProgressRules;
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
        d.register(Commands.literal(Constants.COMMAND).requires(s -> s.hasPermission(2))
                .then(Commands.literal("reset")
                        .then(Commands.argument("players", EntityArgument.players())
                                .executes(c -> each(c, (p, pr) -> resetAll(p, pr)))
                                .then(Commands.argument("id", ResourceLocationArgument.id()).suggests(ANY)
                                        .executes(c -> each(c, (p, pr) -> reset(p, pr, id(c)))))))
                .then(Commands.literal("complete")
                        .then(Commands.argument("players", EntityArgument.players())
                                .then(Commands.argument("id", ResourceLocationArgument.id()).suggests(NODES)
                                        .executes(c -> each(c, (p, pr) -> complete(p, pr, id(c)))))))
                .then(Commands.literal("respec")
                        .then(Commands.argument("players", EntityArgument.players())
                                .then(Commands.argument("id", ResourceLocationArgument.id()).suggests(SKILL_CHAPTERS)
                                        .executes(c -> each(c, (p, pr) -> respec(p, pr, id(c)))))))
                .then(Commands.literal("lock")
                        .then(Commands.argument("players", EntityArgument.players())
                                .then(Commands.argument("id", ResourceLocationArgument.id()).suggests(CHAPTERS)
                                        .executes(c -> each(c, (p, pr) -> chapterOpen(c, pr, false))))))
                .then(Commands.literal("unlock")
                        .then(Commands.argument("players", EntityArgument.players())
                                .then(Commands.argument("id", ResourceLocationArgument.id()).suggests(CHAPTERS)
                                        .executes(c -> each(c, (p, pr) -> chapterOpen(c, pr, true))))))
                .then(Commands.literal("points")
                        .then(Commands.argument("players", EntityArgument.players())
                                .then(Commands.argument("id", ResourceLocationArgument.id()).suggests(SKILL_CHAPTERS)
                                        .then(Commands.argument("amount", IntegerArgumentType.integer())
                                                .executes(c -> each(c, (p, pr) -> pr.bonus.merge(ProgressRules.key(chapter(c)),
                                                        IntegerArgumentType.getInteger(c, "amount"), Integer::sum)))))))
                .then(Commands.literal("experience")
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

    /** Runs the change for every target, then re-checks, saves and syncs. */
    private static int each(CommandContext<CommandSourceStack> c, BiConsumer<ServerPlayer, PlayerProgress> change) throws CommandSyntaxException {
        Collection<ServerPlayer> players = EntityArgument.getPlayers(c, "players");
        try {
            for (ServerPlayer p : players) {
                PlayerProgress pr = ProgressService.progress(p);
                change.accept(p, pr);
                ProgressStore.get(p.server).setDirty();
                ProgressService.recheck(p, pr);
                ProgressService.markDirty(p);
                ProgressService.flush(p);
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

    private static void resetAll(ServerPlayer p, PlayerProgress pr) {
        for (ProgressModel.Chapter ch : ProgressService.rules().model().chapters.values()) {
            resetChapter(p, pr, ch);
        }
        pr.recent.clear();
    }

    /** Pufferfish's Skills' category lock/unlock: a per-player override of {@code points.unlocked_by_default}. */
    private static void chapterOpen(CommandContext<CommandSourceStack> c, PlayerProgress pr, boolean open) {
        ProgressModel.Chapter ch = chapter(c);
        pr.chapterOpen.put(ch.id, open);
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

    private static void resetChapter(ServerPlayer p, PlayerProgress pr, ProgressModel.Chapter ch) {
        for (ProgressModel.Node n : ch.nodes.values()) {
            resetNode(p, pr, n);
        }
        String key = ProgressRules.key(ch);
        pr.xp.remove(key);
        pr.level.remove(key);
        pr.spent.remove(key);
        pr.bonus.remove(key);
        pr.exchange.remove(ch.id);
    }

    private static void resetNode(ServerPlayer p, PlayerProgress pr, ProgressModel.Node n) {
        // removing an id that is not there is harmless, and repeats may have cleared "claimed"
        Integer taken = pr.taken.get(n.key);
        if (taken != null && taken > 0) {
            Rewards.revokeSkill(p, n, taken);
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
        pr.taken.remove(n.key);
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
        for (ProgressModel.Node n : ch.nodes.values()) {
            Integer taken = pr.taken.remove(n.key);
            if (taken != null) {
                Rewards.revokeSkill(p, n, taken);
            }
        }
        pr.spent.remove(ProgressRules.key(ch));
    }
}
