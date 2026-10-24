package com.kuronami.questtree.client;

import com.google.gson.JsonElement;
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
            case "break":
                return new Shown(new ItemStack(Items.IRON_PICKAXE),
                        Component.translatable("task." + p + ".break_named", targetName(spec(o, "block"), "block")));
            case "place_block":
                return new Shown(new ItemStack(Items.GRASS_BLOCK),
                        Component.translatable("task." + p + ".place_block_named", targetName(spec(o, "block"), "block")));
            case "fluid":
                return new Shown(new ItemStack(Items.WATER_BUCKET),
                        Component.translatable("task." + p + ".fluid_named", targetName(spec(o, "fluid"), "block", "fluid")));
            case "energy":
                return new Shown(new ItemStack(Items.REDSTONE), Component.translatable("task." + p + ".energy"));
            case "observation":
                return new Shown(new ItemStack(Items.SPYGLASS), Component.translatable("task." + p + ".observation_named",
                        targetName(spec(o, "target"), switch (str(o, "observe_type")) {
                            case "entity_type", "entity_type_tag" -> "entity";
                            default -> "block";
                        })));
            case "xp":
                return new Shown(new ItemStack(Items.EXPERIENCE_BOTTLE), Component.translatable(o.has("points") && o.get("points").getAsBoolean()
                        ? "task." + p + ".xp_points" : "task." + p + ".xp_levels"));
            case "checkmark":
                return new Shown(ItemStack.EMPTY, Component.translatable("task." + p + ".checkmark"));
            case "craft":
                return new Shown(item.isEmpty() ? new ItemStack(Items.CRAFTING_TABLE) : item,
                        item.isEmpty() ? Component.translatable("task." + p + ".craft")
                                : Component.translatable("task." + p + ".craft_named", item.getHoverName()));
            case "use_item":
                return new Shown(item, item.isEmpty() ? Component.translatable("task." + p + ".use_item")
                        : Component.translatable("task." + p + ".use_item_named", item.getHoverName()));
            case "right_click_block": {
                String spec = spec(o, "block");
                return new Shown(new ItemStack(Items.OAK_BUTTON),
                        Component.translatable("task." + p + ".right_click_block_named", targetName(spec, "block")));
            }
            case "pickup":
                return new Shown(item, item.isEmpty() ? Component.translatable("task." + p + ".pickup")
                        : Component.translatable("task." + p + ".pickup_named", item.getHoverName()));
            case "interact_entity", "tame", "breed", "ride": {
                String entity = entityTarget(o);
                EntityType<?> et = entityId(o);
                SpawnEggItem egg = et == null ? null : SpawnEggItem.byId(et);
                ItemStack icon = new ItemStack(egg != null ? egg : switch (type) {
                    case "tame" -> Items.LEAD;
                    case "breed" -> Items.WHEAT;
                    case "ride" -> Items.SADDLE;
                    default -> Items.EMERALD;
                });
                Component target = et != null ? et.getDescription() : entity.isEmpty()
                        ? Component.translatable("task." + p + ".any_mob") : pretty(entity);
                return new Shown(icon, Component.translatable("task." + p + "." + type + "_named", target));
            }
            case "interaction":
                return describe(str(o, "action").isEmpty() ? str(o, "kind") : str(o, "action"), o, item);
            default:
                return new Shown(ItemStack.EMPTY, Component.translatable("task." + p + "." + type));
        }
    }

    /** The entity a task targets, from {@code entity} or {@code entity_tag}. */
    private static String entityTarget(JsonObject o) {
        String tag = str(o, "entity_tag");
        return tag.isEmpty() ? str(o, "entity") : "#" + tag;
    }

    private static EntityType<?> entityId(JsonObject o) {
        String id = str(o, "entity");
        if (id.isEmpty() || id.startsWith("#")) {
            return null;
        }
        return BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.tryParse(id)).orElse(null);
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

    /**
     * Name of a matched target ("minecraft:water", "#minecraft:logs", "minecraft:oak_stairs[facing=east]"): the first
     * vanilla translation among the given key kinds, else a tidy id.
     */
    private static Component targetName(String spec, String... kinds) {
        String id = spec.startsWith("#") ? spec.substring(1) : spec;
        int bracket = id.indexOf('[');
        if (bracket >= 0) {
            id = id.substring(0, bracket);
        }
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl != null) {
            for (String kind : kinds) {
                String key = kind + "." + rl.getNamespace() + "." + rl.getPath().replace('/', '.');
                if (I18n.exists(key)) {
                    return Component.translatable(key);
                }
            }
        }
        return pretty(id);
    }

    /** A task's matcher field ("block", "fluid") as one string, taking a plain id, {@code #tag}, {@code {id:..}} or {@code {tag:..}}. */
    private static String spec(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null) {
            return "";
        }
        if (e.isJsonPrimitive()) {
            return e.getAsString();
        }
        JsonObject j = e.getAsJsonObject();
        if (j.has("tag")) {
            return "#" + j.get("tag").getAsString();
        }
        if (j.has("id")) {
            return j.get("id").getAsString();
        }
        return j.has("fluid") ? j.get("fluid").getAsString() : "";
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
