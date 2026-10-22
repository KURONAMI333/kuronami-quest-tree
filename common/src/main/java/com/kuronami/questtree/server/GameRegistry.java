package com.kuronami.questtree.server;

import com.kuronami.questtree.platform.Services;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;

import java.util.Optional;

/**
 * The running game's ids for {@link ProgressValidator} (DATA_MODEL.md "Validation"): an unknown item, block, fluid,
 * entity, attribute, biome, structure, dimension, advancement or recipe in the data becomes a WARNING instead of a
 * silent nothing at runtime.
 * <p>
 * The resource-manager reload pass sees this class with no server attached: dynamic registries are being filled and the
 * tags are bound only after every listener has finished, so it answers "known" for those rather than guessing. The
 * post-start pass calls {@link #attach(MinecraftServer)} for the length of one validation, after the levels exist and
 * the tags are bound, and then the dynamic registries, advancements, recipes and tags are checked for real.
 */
public final class GameRegistry implements ProgressValidator.Registry {

    public static final GameRegistry INSTANCE = new GameRegistry();

    private volatile MinecraftServer server;

    private GameRegistry() {
    }

    /** Checks against this server's loaded levels, advancements, recipes and tags until {@link #detach()}. */
    public void attach(MinecraftServer server) {
        this.server = server;
    }

    public void detach() {
        this.server = null;
    }

    @Override
    public boolean economyAvailable() {
        return Services.PLATFORM.isModLoaded("ftblibrary") && FtbLibraryCurrencyBridge.available();
    }

    @Override
    public boolean energyAvailable() {
        return Services.ITEM_STORAGE.supportsEnergy();
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
            case "dimension" -> server == null || registryOr(Registries.LEVEL_STEM).containsKey(rl);
            case "biome" -> server == null || registryOr(Registries.BIOME).containsKey(rl);
            case "structure" -> server == null || registryOr(Registries.STRUCTURE).containsKey(rl);
            case "advancement" -> server == null || server.getAdvancements().get(rl) != null;
            case "recipe" -> server == null || server.getRecipeManager().byKey(rl).isPresent();
            default -> true;
        };
    }

    @Override
    public ProgressValidator.TagStatus tag(String kind, String id) {
        MinecraftServer current = server;
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (current == null || rl == null) {
            return ProgressValidator.TagStatus.OK;
        }
        return switch (kind) {
            case "item" -> tagOf(BuiltInRegistries.ITEM, Registries.ITEM, rl);
            case "block" -> tagOf(BuiltInRegistries.BLOCK, Registries.BLOCK, rl);
            case "fluid" -> tagOf(BuiltInRegistries.FLUID, Registries.FLUID, rl);
            case "entity" -> tagOf(BuiltInRegistries.ENTITY_TYPE, Registries.ENTITY_TYPE, rl);
            case "attribute" -> tagOf(BuiltInRegistries.ATTRIBUTE, Registries.ATTRIBUTE, rl);
            case "biome" -> tagOf(current.registryAccess().registryOrThrow(Registries.BIOME), Registries.BIOME, rl);
            case "structure" -> tagOf(current.registryAccess().registryOrThrow(Registries.STRUCTURE), Registries.STRUCTURE, rl);
            default -> ProgressValidator.TagStatus.OK;
        };
    }

    private <T> Registry<T> registryOr(ResourceKey<? extends Registry<T>> key) {
        MinecraftServer current = server;
        return current == null ? null : current.registryAccess().registryOrThrow(key);
    }

    private static <T> ProgressValidator.TagStatus tagOf(Registry<T> registry, ResourceKey<? extends Registry<T>> key,
                                                         ResourceLocation id) {
        Optional<HolderSet.Named<T>> tag = registry.getTag(TagKey.create(key, id));
        if (tag.isEmpty()) {
            return ProgressValidator.TagStatus.MISSING;
        }
        return tag.get().size() == 0 ? ProgressValidator.TagStatus.EMPTY : ProgressValidator.TagStatus.OK;
    }
}
