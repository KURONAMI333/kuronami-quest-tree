package com.kuronami.questtree.server;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/**
 * The running game's ids for {@link ProgressValidator} (DATA_MODEL.md "検査"): an unknown item, block, fluid, entity
 * or attribute in the data becomes a WARNING instead of a silent nothing at runtime.
 * <p>
 * Only plain ids are asked about, and only those the built-in registries hold. Two kinds are deliberately answered
 * true, so that data is never warned about for a reason that has nothing to do with it:
 * <ul>
 *     <li>Tags are not looked up. The game binds tag contents in {@code TagManager.updateRegistryTags} only after every
 *     reload listener has finished, so a listener that asks now would read an empty tag on the first start and last
 *     reload's contents on {@code /reload}. Both would be wrong, and the tag's own shape is checked by the validator.
 *     </li>
 *     <li>Dynamic registries (biome, structure, dimension, stat) are not reachable from the resource manager that the
 *     listener is given, so they are reported as unknown rather than guessed at.</li>
 * </ul>
 */
public final class GameRegistry implements ProgressValidator.Registry {

    public static final GameRegistry INSTANCE = new GameRegistry();

    private GameRegistry() {
    }

    @Override
    public boolean known(String kind, String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) {
            return false;
        }
        return switch (kind) {
            case "item" -> BuiltInRegistries.ITEM.containsKey(rl);
            case "block" -> BuiltInRegistries.BLOCK.containsKey(rl);
            case "fluid" -> BuiltInRegistries.FLUID.containsKey(rl);
            case "entity" -> BuiltInRegistries.ENTITY_TYPE.containsKey(rl);
            case "attribute" -> BuiltInRegistries.ATTRIBUTE.containsKey(rl);
            default -> true;
        };
    }
}
