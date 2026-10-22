package com.kuronami.questtree.client;

import com.google.gson.JsonObject;
import com.kuronami.questtree.data.ProgressModel;
import com.kuronami.questtree.progress.PlayerProgress;
import com.kuronami.questtree.progress.ProgressRules;
import com.kuronami.questtree.progress.ProgressRules.State;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Needed for" on item tooltips (EXTERNAL_REFERENCES 1-4; Stardew Valley's bundle hint, WoW's quest progress on
 * items): when an item is wanted by a pinned or open quest, its tooltip gets the quest title and the count, at most two
 * quests plus "+n". Values and labels only, no explanation. Off with item_tooltips in the client settings.
 */
public final class ItemTooltips {

    private record Want(String node, int task, com.kuronami.questtree.progress.ItemRequirement requirement) {
    }

    private static final Map<String, List<Want>> BY_ITEM = new HashMap<>();
    private static final List<Want> BY_TAG = new ArrayList<>();
    private static Object builtFor;
    private static Object builtRules;
    private static int builtPins = -1;

    private ItemTooltips() {
    }

    /** Called by the loader's tooltip event. */
    public static void append(ItemStack stack, List<Component> lines) {
        // creative and recipe search build their indexes on worker threads and ask for tooltips there: never answer those
        if (!net.minecraft.client.Minecraft.getInstance().isSameThread()) {
            return;
        }
        ClientConfig.ensureLoaded();
        if (stack.isEmpty()) {
            return;
        }
        appendRestriction(stack, lines);
        ProgressRules rules = ClientProgress.rules();
        ClientModel model = ClientProgress.model();
        if (!ClientConfig.itemTooltips || rules == null || model == null) {
            return;
        }
        PlayerProgress p = ClientProgress.progress();
        ensureIndex(rules, p);
        var registries = net.minecraft.client.Minecraft.getInstance().level == null ? null
                : net.minecraft.client.Minecraft.getInstance().level.registryAccess();
        List<Want> wants = new ArrayList<>();
        for (Want w : BY_ITEM.getOrDefault(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), List.of())) {
            if (w.requirement.matches(stack, registries)) {
                wants.add(w);
            }
        }
        for (Want w : BY_TAG) {
            if (w.requirement.matches(stack, registries)) {
                wants.add(w);
            }
        }
        int shown = 0;
        for (Want w : wants) {
            if (shown == 2) {
                lines.add(Component.literal("+" + (wants.size() - 2)).withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
                break;
            }
            ClientModel.Node n = model.nodesByKey.get(w.node);
            if (n == null || w.task < 0 || w.task >= n.tasks.size()) {
                continue;
            }
            long need = n.tasks.get(w.task).count;
            long have = Math.min(p.task(w.node, w.task), need);
            lines.add(Component.empty()
                    .append(Component.literal(n.title.getString()).withColor(Ui.BADGE & 0xFFFFFF))
                    .append(Component.literal(" " + have + "/" + need).withStyle(net.minecraft.ChatFormatting.GRAY)));
            shown++;
        }
    }

    /** The reason an unmet restriction gives this item, or the generic line when the pack wrote none. */
    private static void appendRestriction(ItemStack stack, List<Component> lines) {
        String hint = ClientProgress.restrictionHint(stack);
        if (hint == null) {
            return;
        }
        lines.add(hint.isBlank()
                ? Component.translatable("message.kuronami_quest_tree.restricted")
                        .withStyle(net.minecraft.ChatFormatting.GRAY)
                : Component.literal(hint).withStyle(net.minecraft.ChatFormatting.GRAY));
    }

    /**
     * The quest that wants this item most (pinned first, then open ones), or null: the open key over an item opens it.
     * Uses the same index as the tooltips.
     */
    public static String questFor(ItemStack stack) {
        ProgressRules rules = ClientProgress.rules();
        if (rules == null || ClientProgress.model() == null || stack.isEmpty()) {
            return null;
        }
        PlayerProgress p = ClientProgress.progress();
        ensureIndex(rules, p);
        var registries = net.minecraft.client.Minecraft.getInstance().level == null ? null
                : net.minecraft.client.Minecraft.getInstance().level.registryAccess();
        for (Want w : BY_ITEM.getOrDefault(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), List.of())) {
            if (w.requirement.matches(stack, registries)) {
                return w.node;
            }
        }
        for (Want w : BY_TAG) {
            if (w.requirement.matches(stack, registries)) {
                return w.node;
            }
        }
        return null;
    }

    /**
     * Rebuilds the index only when the progress, the rules or the pins changed. The rules are a new object on every
     * editor resync, so a replaced model (deleted or renumbered tasks) must not be read through an old index.
     */
    private static void ensureIndex(ProgressRules rules, PlayerProgress p) {
        int pins = Pins.version();
        if (IndexFreshness.same(builtFor, builtRules, builtPins, p, rules, pins)) {
            return;
        }
        build(rules, p);
        builtFor = p;
        builtRules = rules;
        builtPins = pins;
    }

    /** Index of the items that pinned quests and quests open right now still need. */
    private static void build(ProgressRules rules, PlayerProgress p) {
        BY_ITEM.clear();
        BY_TAG.clear();
        Map<String, Boolean> seen = new HashMap<>();
        for (ProgressModel.Node n : rules.model().nodes.values()) {
            State s = rules.state(p, n);
            boolean wanted = Pins.has(n.key) ? s != State.DONE : (s == State.OPEN || s == State.PROGRESS);
            if (!wanted || !rules.visible(p, n, seen)) {
                continue;
            }
            for (int i = 0; i < n.tasks.size(); i++) {
                JsonObject t = n.tasks.get(i);
                if (!"item".equals(t.has("type") ? t.get("type").getAsString() : "") || p.task(n.key, i) >= ProgressRules.target(t)) {
                    continue;
                }
                var requirement = com.kuronami.questtree.progress.ItemRequirement.of(t);
                String spec = requirement.spec();
                if (spec.startsWith("#") || spec.startsWith("~")) {
                    BY_TAG.add(new Want(n.key, i, requirement));
                } else if (!spec.isEmpty()) {
                    BY_ITEM.computeIfAbsent(spec, k -> new ArrayList<>()).add(new Want(n.key, i, requirement));
                }
            }
        }
        // pinned quests first
        BY_ITEM.values().forEach(l -> l.sort((a, b) -> Boolean.compare(Pins.has(b.node), Pins.has(a.node))));
    }
}
