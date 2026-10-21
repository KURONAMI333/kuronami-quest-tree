package com.kuronami.questtree.client;

import com.kuronami.questtree.client.editor.FieldSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * The lists the pickers search (2026-10-21, phase 3), read from the client's own registries. A registry the client does
 * not have (structures are not sent to clients) gives a short built-in list, and every picker still takes typed text, so
 * an id a list lacks is never out of reach.
 */
final class EditPickers {

    /** One pickable id with its shown name and, when it has one, an item to draw beside it. */
    record Option(String id, Component label, ItemStack icon) {
    }

    private static final List<String> VANILLA_STRUCTURES = List.of(
            "minecraft:village_plains", "minecraft:village_desert", "minecraft:village_savanna",
            "minecraft:village_snowy", "minecraft:village_taiga", "minecraft:stronghold", "minecraft:mineshaft",
            "minecraft:mineshaft_mesa", "minecraft:monument", "minecraft:mansion", "minecraft:fortress",
            "minecraft:bastion_remnant", "minecraft:end_city", "minecraft:ancient_city", "minecraft:trial_chambers",
            "minecraft:trail_ruins", "minecraft:desert_pyramid", "minecraft:jungle_pyramid", "minecraft:igloo",
            "minecraft:swamp_hut", "minecraft:pillager_outpost", "minecraft:ocean_ruin_cold",
            "minecraft:ocean_ruin_warm", "minecraft:shipwreck", "minecraft:shipwreck_beached",
            "minecraft:buried_treasure", "minecraft:ruined_portal", "minecraft:nether_fossil");

    private EditPickers() {
    }

    /** The stack an item id draws as; empty for a tag, a blank, or an id nothing is registered under. */
    static ItemStack stackOf(String id) {
        if (id == null || id.isBlank() || id.startsWith("#")) {
            return ItemStack.EMPTY;
        }
        ResourceLocation rl = ResourceLocation.tryParse(id.trim());
        if (rl == null) {
            return ItemStack.EMPTY;
        }
        Item item = BuiltInRegistries.ITEM.get(rl);
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    static List<Option> options(FieldSpec.Kind kind) {
        List<Option> out = new ArrayList<>();
        ClientPacketListener conn = Minecraft.getInstance().getConnection();
        switch (kind) {
            case ITEM -> {
                for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
                    Item item = BuiltInRegistries.ITEM.get(id);
                    if (item != Items.AIR) {
                        ItemStack stack = new ItemStack(item);
                        out.add(new Option(id.toString(), stack.getHoverName(), stack));
                    }
                }
            }
            case ENTITY -> {
                for (ResourceLocation id : BuiltInRegistries.ENTITY_TYPE.keySet()) {
                    out.add(new Option(id.toString(), BuiltInRegistries.ENTITY_TYPE.get(id).getDescription(),
                            ItemStack.EMPTY));
                }
            }
            case DIMENSION -> {
                TreeMap<String, Option> found = new TreeMap<>();
                for (String id : List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end")) {
                    found.put(id, named(id, "dimension"));
                }
                if (conn != null) {
                    conn.levels().forEach(key -> found.put(key.location().toString(), named(key.location().toString(),
                            "dimension")));
                }
                out.addAll(found.values());
            }
            case BIOME -> {
                if (conn != null) {
                    conn.registryAccess().registry(Registries.BIOME).ifPresent(r -> addAll(out, r, "biome"));
                }
            }
            case STRUCTURE -> {
                boolean any = false;
                if (conn != null) {
                    var reg = conn.registryAccess().registry(Registries.STRUCTURE);
                    if (reg.isPresent() && reg.get().size() > 0) {
                        addAll(out, reg.get(), "structure");
                        any = true;
                    }
                }
                if (!any) {
                    VANILLA_STRUCTURES.forEach(id -> out.add(named(id, "structure")));
                }
            }
            case ADVANCEMENT -> {
                if (conn != null) {
                    conn.getAdvancements().getTree().nodes().forEach(node -> {
                        var holder = node.holder();
                        Component label = holder.value().display().map(d -> d.getTitle())
                                .orElse(Component.literal(holder.id().toString()));
                        out.add(new Option(holder.id().toString(), label,
                                holder.value().display().map(d -> d.getIcon()).orElse(ItemStack.EMPTY)));
                    });
                }
            }
            default -> {
            }
        }
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return out;
    }

    private static void addAll(List<Option> out, Registry<?> registry, String type) {
        for (ResourceLocation id : registry.keySet()) {
            out.add(named(id.toString(), type));
        }
    }

    private static Option named(String id, String type) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        Component label = rl == null ? Component.literal(id)
                : Component.translatableWithFallback(rl.toLanguageKey(type), prettify(rl.getPath()));
        return new Option(id, label, ItemStack.EMPTY);
    }

    private static String prettify(String path) {
        String[] words = path.replace('/', ' ').replace('_', ' ').split(" ");
        StringBuilder out = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(w.substring(0, 1).toUpperCase(Locale.ROOT)).append(w.substring(1));
        }
        return out.toString();
    }
}
