package com.kuronami.questtree.client;

import com.google.gson.JsonObject;
import com.kuronami.questtree.data.ProgressModel;
import com.kuronami.questtree.progress.PlayerProgress;
import com.kuronami.questtree.progress.ProgressRules;
import com.kuronami.questtree.progress.ProgressRules.State;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
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

    private record Want(String node, int task, String spec) {
    }

    private static final Map<String, List<Want>> BY_ITEM = new HashMap<>();
    private static final List<Want> BY_TAG = new ArrayList<>();
    private static Object builtFor;

    private ItemTooltips() {
    }

    /** Called by the loader's tooltip event. */
    public static void append(ItemStack stack, List<Component> lines) {
        ClientConfig.ensureLoaded();
        ProgressRules rules = ClientProgress.rules();
        ClientModel model = ClientProgress.model();
        if (!ClientConfig.itemTooltips || rules == null || model == null || stack.isEmpty()) {
            return;
        }
        PlayerProgress p = ClientProgress.progress();
        if (builtFor != p) {
            build(rules, p);
            builtFor = p;
        }
        List<Want> wants = new ArrayList<>(BY_ITEM.getOrDefault(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), List.of()));
        for (Want w : BY_TAG) {
            ResourceLocation tag = ResourceLocation.tryParse(w.spec.substring(1));
            if (tag != null && stack.is(TagKey.create(Registries.ITEM, tag))) {
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
            if (n == null) {
                continue;
            }
            long need = n.tasks.get(w.task).count;
            long have = Math.min(p.task(w.node, w.task), need);
            lines.add(Component.empty()
                    .append(Component.literal(n.title.getString()).withColor(0xF2C230))
                    .append(Component.literal(" " + have + "/" + need).withStyle(net.minecraft.ChatFormatting.GRAY)));
            shown++;
        }
    }

    /** Index of the items that pinned quests and quests open right now still need. */
    private static void build(ProgressRules rules, PlayerProgress p) {
        BY_ITEM.clear();
        BY_TAG.clear();
        for (ProgressModel.Node n : rules.model().nodes.values()) {
            State s = rules.state(p, n);
            boolean wanted = Pins.has(n.key) ? s != State.DONE : (s == State.OPEN || s == State.PROGRESS);
            if (!wanted || !rules.visible(p, n)) {
                continue;
            }
            for (int i = 0; i < n.tasks.size(); i++) {
                JsonObject t = n.tasks.get(i);
                if (!"item".equals(t.has("type") ? t.get("type").getAsString() : "") || p.task(n.key, i) >= ProgressRules.target(t)) {
                    continue;
                }
                String spec = spec(t);
                if (spec.startsWith("#")) {
                    BY_TAG.add(new Want(n.key, i, spec));
                } else if (!spec.isEmpty()) {
                    BY_ITEM.computeIfAbsent(spec, k -> new ArrayList<>()).add(new Want(n.key, i, spec));
                }
            }
        }
        // pinned quests first
        BY_ITEM.values().forEach(l -> l.sort((a, b) -> Boolean.compare(Pins.has(b.node), Pins.has(a.node))));
    }

    private static String spec(JsonObject task) {
        var it = task.get("item");
        if (it == null) {
            return "";
        }
        if (it.isJsonPrimitive()) {
            return it.getAsString();
        }
        JsonObject o = it.getAsJsonObject();
        return o.has("tag") ? "#" + o.get("tag").getAsString() : o.has("id") ? o.get("id").getAsString() : "";
    }
}
