package com.kuronami.questtree.client.compat;

import com.kuronami.questtree.Constants;
import com.kuronami.questtree.client.ClientProgress;
import com.kuronami.questtree.client.ProgressScreen;
import com.kuronami.questtree.client.QuestViewerStyle;
import com.kuronami.questtree.client.viewer.QuestViewerData;
import com.kuronami.questtree.client.viewer.QuestViewerIndex.Hit;
import com.kuronami.questtree.client.viewer.QuestViewerIndex;
import com.kuronami.questtree.client.viewer.QuestViewerIndex.Kind;
import com.kuronami.questtree.client.viewer.QuestViewerIndex.Need;
import com.kuronami.questtree.client.viewer.QuestViewerIndex.Spec;
import com.kuronami.questtree.item.ModItems;
import com.kuronami.questtree.progress.ItemFilter;
import com.kuronami.questtree.progress.ProgressRules;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One viewer entry: a quest and the task it needs or the reward it gives. The player state is read when asked, so a
 * quest finished while a viewer is open keeps its wording (each viewer redraws from this object).
 */
public final class QuestViewerRecipe {

    public static final int WIDTH = 170;
    public static final int HEIGHT = 64;

    private final Hit hit;
    private final List<ItemStack> stacks;
    private final ItemStack icon;

    public QuestViewerRecipe(Hit hit) {
        this.hit = hit;
        this.stacks = List.copyOf(stacksOf(hit.need()));
        this.icon = iconOf(hit);
    }

    public Hit hit() {
        return hit;
    }

    public List<ItemStack> stacks() {
        return stacks;
    }

    public ItemStack icon() {
        return icon;
    }

    public boolean needsItem() {
        return hit.need().kind() == Kind.TASK;
    }

    /** The quest title, resolved now so a language change or a translatable title follows the current language. */
    public Component titleComponent() {
        return text(hit.quest().title(), hit.quest().title());
    }

    public String title() {
        return titleComponent().getString();
    }

    public Component chapterComponent() {
        return text(hit.quest().chapterTitle(), hit.quest().chapterTitle());
    }

    public String chapter() {
        return chapterComponent().getString();
    }

    /** The row's label with the real count, resolved when drawn rather than when the bundle arrived. */
    public Component needLabelComponent() {
        Need need = hit.need();
        Component label;
        if (!need.labelJson().isEmpty()) {
            label = text(need.labelJson(), need.label());
        } else if (!need.spec().tag() && !stacks.isEmpty()) {
            label = stacks.get(0).getHoverName();
        } else {
            label = Component.literal(need.label().isEmpty() ? need.spec().id() : need.label());
        }
        if (need.choice()) {
            label = Component.translatable("kuronami_quest_tree.viewer.choice", label);
        } else if (need.random()) {
            label = Component.translatable("kuronami_quest_tree.viewer.random", label);
        }
        if (need.count() > 1) {
            label = Component.empty().append(label).append(" × " + need.count());
        }
        return label;
    }

    public String needLabel() {
        return needLabelComponent().getString();
    }

    /** Reads a Component serialized when the bundle arrived; plain text (a test or an old bundle) is literal. */
    private static Component text(String json, String fallback) {
        if (json == null || json.isEmpty()) {
            return Component.literal(fallback == null ? "" : fallback);
        }
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level == null) {
            return Component.literal(fallback == null ? json : fallback);
        }
        try {
            return Component.Serializer.fromJson(json, level.registryAccess());
        } catch (RuntimeException ex) {
            return Component.literal(fallback == null ? json : fallback);
        }
    }

    public ProgressRules.State state() {
        return ClientProgress.state(hit.quest().key());
    }

    public Component stateLabel() {
        return QuestViewerStyle.stateLabel(state());
    }

    public int stateColor() {
        return QuestViewerStyle.stateColor(state());
    }

    public void open() {
        ProgressScreen.openAt(hit.quest().key());
    }

    public ResourceLocation id() {
        String key = hit.quest().key().toLowerCase(Locale.ROOT)
                .replace(':', '/').replaceAll("[^a-z0-9_./-]", "_");
        String kind = needsItem() ? "task" : "reward";
        int hash = (hit.quest().key() + '|' + kind + '|' + hit.need().index() + '|'
                + hit.need().spec().kind() + '|' + hit.need().spec().id() + '|' + hit.need().count() + '|'
                + hit.need().choice() + '|' + hit.need().random() + '|' + hit.need().itemJson()).hashCode();
        return ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID,
                "quest/" + key + "/" + kind + "/" + hit.need().index() + "/" + Integer.toHexString(hash));
    }

    /** The display stacks of one reward table entry, used by the crate preview. */
    public static List<ItemStack> stacksOfReward(com.google.gson.JsonObject reward) {
        if (reward == null) {
            return List.of();
        }
        String type = QuestViewerData.string(reward, "type");
        if (!type.isEmpty() && !type.equals("item")) {
            return List.of();
        }
        com.google.gson.JsonElement item = reward.get("item");
        if (item == null || item.isJsonNull()) {
            return List.of();
        }
        Spec spec = QuestViewerData.specOf(item);
        if (spec.id().isEmpty()) {
            return List.of();
        }
        Need need = new Need(Kind.REWARD, spec, Math.max(1, QuestViewerData.countOf(item)), "", "", 0,
                false, false, item.isJsonObject() ? item.toString() : "", "");
        return stacksOf(need);
    }

    public static List<ItemStack> stacksOf(Need need) {
        Spec spec = need.spec();
        if (spec == null || spec.id().isEmpty()) {
            return List.of();
        }
        int amount = (int) Math.max(1, Math.min(99, need.count()));
        List<ItemStack> out = new ArrayList<>();
        if (spec.tag()) {
            ResourceLocation tagId = ResourceLocation.tryParse(spec.id());
            if (tagId == null) {
                return List.of();
            }
            List<Item> members = new ArrayList<>();
            BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, tagId))
                    .ifPresent(tag -> tag.forEach(holder -> members.add(holder.value())));
            // no cap: JEI hides a recipe whose slot lacks the focused item, so a big tag must list every member
            for (Item item : QuestViewerIndex.tagEntries(members)) {
                if (item != Items.AIR) {
                    out.add(new ItemStack(item, amount));
                }
            }
        } else if (spec.filter()) {
            for (ItemStack example : ItemFilter.parse(spec.id()).examples()) {
                if (!example.isEmpty()) {
                    out.add(example.copyWithCount(amount));
                }
            }
        } else {
            ItemStack withComponents = stackFromJson(need.itemJson(), amount);
            if (!withComponents.isEmpty()) {
                out.add(withComponents);
            } else {
                ResourceLocation id = ResourceLocation.tryParse(spec.id());
                if (id != null) {
                    Item item = BuiltInRegistries.ITEM.get(id);
                    if (item != Items.AIR) {
                        out.add(new ItemStack(item, amount));
                    }
                }
            }
        }
        return out;
    }

    /** The task's or reward's item JSON as a display stack, components included; empty when it cannot be read. */
    private static ItemStack stackFromJson(String json, int amount) {
        if (json == null || json.isEmpty()) {
            return ItemStack.EMPTY;
        }
        try {
            com.google.gson.JsonObject object = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            object.remove("count");
            object.addProperty("count", amount);
            var level = net.minecraft.client.Minecraft.getInstance().level;
            var ops = level == null ? com.mojang.serialization.JsonOps.INSTANCE
                    : level.registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
            return ItemStack.CODEC.parse(ops, object).result().orElse(ItemStack.EMPTY);
        } catch (RuntimeException ex) {
            return ItemStack.EMPTY;
        }
    }

    private static ItemStack iconOf(Hit hit) {
        if (!hit.quest().iconItemId().isEmpty()) {
            ResourceLocation id = ResourceLocation.tryParse(hit.quest().iconItemId());
            if (id != null) {
                Item item = BuiltInRegistries.ITEM.get(id);
                if (item != Items.AIR) {
                    return new ItemStack(item);
                }
            }
        }
        return book();
    }

    /** The mod's quest book, or vanilla's when the item is somehow not registered yet. */
    public static ItemStack book() {
        Item book = ModItems.questBook();
        return new ItemStack(book == null ? Items.BOOK : book);
    }
}
