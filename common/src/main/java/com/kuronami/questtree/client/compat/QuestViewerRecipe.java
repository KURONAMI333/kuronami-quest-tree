package com.kuronami.questtree.client.compat;

import com.kuronami.questtree.Constants;
import com.kuronami.questtree.client.ClientProgress;
import com.kuronami.questtree.client.ProgressScreen;
import com.kuronami.questtree.client.QuestViewerStyle;
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

    public String title() {
        return hit.quest().title();
    }

    public String chapter() {
        return hit.quest().chapterTitle();
    }

    public String needLabel() {
        String label = hit.need().choice()
                ? Component.translatable("viewer.choice", hit.need().label()).getString()
                : hit.need().label();
        return hit.need().count() > 1 ? label + " × " + hit.need().count() : label;
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
                + hit.need().spec().id() + '|' + hit.need().choice()).hashCode();
        return ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID,
                "quest/" + key + "/" + kind + "/" + hit.need().index() + "/" + Integer.toHexString(hash));
    }

    private static List<ItemStack> stacksOf(Need need) {
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
