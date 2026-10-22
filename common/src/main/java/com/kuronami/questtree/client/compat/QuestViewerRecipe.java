package com.kuronami.questtree.client.compat;

import com.kuronami.questtree.Constants;
import com.kuronami.questtree.client.ClientProgress;
import com.kuronami.questtree.client.ProgressScreen;
import com.kuronami.questtree.client.QuestViewerStyle;
import com.kuronami.questtree.client.viewer.QuestViewerIndex.Hit;
import com.kuronami.questtree.client.viewer.QuestViewerIndex.Kind;
import com.kuronami.questtree.client.viewer.QuestViewerIndex.Spec;
import com.kuronami.questtree.item.ModItems;
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
        this.stacks = List.copyOf(stacksOf(hit.need().spec(), hit.need().count()));
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

    private static List<ItemStack> stacksOf(Spec spec, long count) {
        if (spec == null || spec.id().isEmpty()) {
            return List.of();
        }
        int amount = (int) Math.max(1, Math.min(99, count));
        List<ItemStack> out = new ArrayList<>();
        if (spec.tag()) {
            ResourceLocation tagId = ResourceLocation.tryParse(spec.id());
            if (tagId == null) {
                return List.of();
            }
            BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, tagId)).ifPresent(tag -> {
                for (var holder : tag) {
                    out.add(new ItemStack(holder.value(), amount));
                    if (out.size() >= 64) {
                        break;
                    }
                }
            });
        } else {
            ResourceLocation id = ResourceLocation.tryParse(spec.id());
            if (id != null) {
                Item item = BuiltInRegistries.ITEM.get(id);
                if (item != Items.AIR) {
                    out.add(new ItemStack(item, amount));
                }
            }
        }
        return out;
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
