package com.kuronami.questtree.server;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;

/**
 * The running game's ids for {@link ProgressValidator} (DATA_MODEL.md "検査"): an unknown item, block, fluid, entity
 * or attribute in the data becomes a WARNING instead of a silent nothing at runtime.
 * <p>
 * Dynamic registries (biome, structure, dimension, stat) are not checked here: they are not cheap to reach from a
 * reload listener, so {@code known} answers true for them and no false warning is made.
 */
public final class GameRegistry implements ProgressValidator.Registry {

    public static final GameRegistry INSTANCE = new GameRegistry();

    private GameRegistry() {
    }

    @Override
    public boolean known(String kind, String id) {
        boolean tag = id.startsWith("#");
        ResourceLocation rl = ResourceLocation.tryParse(tag ? id.substring(1) : id);
        if (rl == null) {
            return false;
        }
        return switch (kind) {
            case "item" -> tag
                    ? BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, rl)).isPresent()
                    : BuiltInRegistries.ITEM.containsKey(rl);
            case "block" -> tag
                    ? BuiltInRegistries.BLOCK.getTag(TagKey.create(Registries.BLOCK, rl)).isPresent()
                    : BuiltInRegistries.BLOCK.containsKey(rl);
            case "fluid" -> tag
                    ? BuiltInRegistries.FLUID.getTag(TagKey.create(Registries.FLUID, rl)).isPresent()
                    : BuiltInRegistries.FLUID.containsKey(rl);
            case "entity" -> tag
                    ? BuiltInRegistries.ENTITY_TYPE.getTag(TagKey.create(Registries.ENTITY_TYPE, rl)).isPresent()
                    : BuiltInRegistries.ENTITY_TYPE.containsKey(rl);
            case "attribute" -> !tag && BuiltInRegistries.ATTRIBUTE.containsKey(rl);
            default -> true;
        };
    }
}
