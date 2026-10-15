package com.kuronami.questtree.client;

import com.google.gson.JsonObject;
import com.kuronami.questtree.Constants;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;

import java.util.Locale;

/**
 * Icon and name of a task row, naming the target: "Defeat Zombie" with a zombie spawn egg, "Visit Plains" with a map,
 * the advancement's own title and icon. Before this, rows only said the kind of task ("Kill 0/5"), not what to do.
 */
final class TaskText {

    /** {@code resolved} is false only while an advancement's display is not known on the client yet. */
    record Shown(ItemStack icon, Component name, boolean resolved) {
        Shown(ItemStack icon, Component name) {
            this(icon, name, true);
        }
    }

    private TaskText() {
    }

    static Shown describe(String type, JsonObject o, ItemStack item) {
        String p = Constants.MOD_ID;
        switch (type) {
            case "item":
                return new Shown(item, item.isEmpty() ? Component.translatable("task." + p + ".item") : item.getHoverName());
            case "kill": {
                String id = str(o, "entity");
                EntityType<?> et = id.isEmpty() || id.startsWith("#") ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.tryParse(id)).orElse(null);
                Component target = et != null ? et.getDescription() : id.isEmpty() ? Component.translatable("task." + p + ".any_mob") : pretty(id);
                SpawnEggItem egg = et == null ? null : SpawnEggItem.byId(et);
                return new Shown(new ItemStack(egg != null ? egg : Items.IRON_SWORD), Component.translatable("task." + p + ".kill_named", target));
            }
            case "advancement": {
                ResourceLocation rl = ResourceLocation.tryParse(str(o, "advancement"));
                var conn = Minecraft.getInstance().getConnection();
                AdvancementHolder h = rl == null || conn == null ? null : conn.getAdvancements().get(rl);
                if (h != null && h.value().display().isPresent()) {
                    var d = h.value().display().get();
                    return new Shown(d.getIcon(), Component.translatable("task." + p + ".advancement_named", d.getTitle()));
                }
                // not sent to the client yet (hidden advancements stay hidden until earned)
                return new Shown(new ItemStack(Items.KNOWLEDGE_BOOK), Component.translatable("task." + p + ".advancement_named",
                        rl == null ? Component.literal("?") : pretty(rl.toString())), h != null);
            }
            case "biome": {
                String id = str(o, "biome");
                return new Shown(new ItemStack(Items.MAP), Component.translatable("task." + p + ".biome_named", registryName("biome", id)));
            }
            case "dimension": {
                String id = str(o, "dimension");
                ItemStack icon = new ItemStack(switch (id) {
                    case "minecraft:the_nether" -> Items.NETHERRACK;
                    case "minecraft:the_end" -> Items.END_STONE;
                    case "minecraft:overworld" -> Items.GRASS_BLOCK;
                    default -> Items.ENDER_PEARL;
                });
                return new Shown(icon, Component.translatable("task." + p + ".dimension_named", dimensionName(id)));
            }
            case "structure":
                return new Shown(new ItemStack(Items.COMPASS), Component.translatable("task." + p + ".structure_named", pretty(str(o, "structure"))));
            case "location":
                return new Shown(new ItemStack(Items.COMPASS), Component.translatable("task." + p + ".location"));
            case "stat": {
                String id = str(o, "stat");
                return new Shown(new ItemStack(Items.WRITABLE_BOOK), registryName("stat", id));
            }
            case "stage":
                return new Shown(new ItemStack(Items.NAME_TAG), Component.translatable("task." + p + ".stage_named", pretty(str(o, "stage"))));
            case "xp":
                return new Shown(new ItemStack(Items.EXPERIENCE_BOTTLE), Component.translatable(o.has("points") && o.get("points").getAsBoolean()
                        ? "task." + p + ".xp_points" : "task." + p + ".xp_levels"));
            case "checkmark":
                return new Shown(ItemStack.EMPTY, Component.translatable("task." + p + ".checkmark"));
            default:
                return new Shown(ItemStack.EMPTY, Component.translatable("task." + p + "." + type));
        }
    }

    /** Vanilla-style translation ("biome.minecraft.plains", "stat.minecraft.jump") when one exists, else a tidy id. */
    private static Component registryName(String kind, String id) {
        if (id.startsWith("#")) {
            return pretty(id.substring(1));
        }
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) {
            return Component.literal(id);
        }
        String key = kind + "." + rl.getNamespace() + "." + rl.getPath().replace('/', '.');
        return I18n.exists(key) ? Component.translatable(key) : pretty(id);
    }

    private static Component dimensionName(String id) {
        String key = "task." + Constants.MOD_ID + ".dim." + id.replace(':', '.');
        return I18n.exists(key) ? Component.translatable(key) : pretty(id);
    }

    /** "minecraft:ancient_city" -> "Ancient City" (ids without a translation). */
    static Component pretty(String id) {
        String path = id.substring(id.indexOf(':') + 1);
        path = path.substring(path.lastIndexOf('/') + 1);
        StringBuilder b = new StringBuilder();
        for (String w : path.split("[_ ]+")) {
            if (!w.isEmpty()) {
                b.append(b.isEmpty() ? "" : " ").append(w.substring(0, 1).toUpperCase(Locale.ROOT)).append(w.substring(1));
            }
        }
        return Component.literal(b.toString());
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }
}
