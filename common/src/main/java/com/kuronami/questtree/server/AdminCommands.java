package com.kuronami.questtree.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuronami.questtree.Constants;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The book-wide commands of FTB Quests that the progress commands do not cover (2026-10-22): opening the book for a
 * player, making a chapter from items, moving a reward table to and from a container, deleting empty tables, clearing
 * the item display cache and cleaning stale translations. All need permission level 2, like FTB Quests' editor
 * commands. Writes go through {@link EditorService}, so the same validation, revisions and publish path apply.
 * <pre>
 * /quest_tree open_book [chapter or node]
 * /quest_tree chapter generate creative|inventory|container &lt;pos&gt;
 * /quest_tree table export &lt;table&gt; [pos]
 * /quest_tree table import &lt;name&gt; [pos]
 * /quest_tree table delete_empty
 * /quest_tree clear_display_cache
 * /quest_tree cleanup_translations show|execute
 * </pre>
 */
final class AdminCommands {

    private static final SuggestionProvider<CommandSourceStack> TABLES = (c, b) -> {
        JsonObject tables = ProgressData.mergedBundle().getAsJsonObject("reward_tables");
        return SharedSuggestionProvider.suggest(tables == null ? List.of() : tables.keySet(), b);
    };
    private static final SuggestionProvider<CommandSourceStack> TARGETS = (c, b) -> SharedSuggestionProvider.suggest(
            java.util.stream.Stream.concat(ProgressService.rules().model().chapters.keySet().stream(),
                    ProgressService.rules().model().nodes.keySet().stream()), b);
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private AdminCommands() {
    }

    static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal(Constants.COMMAND)
                .then(Commands.literal("open_book").requires(src -> src.hasPermission(2))
                        .executes(AdminCommands::openBook)
                        .then(Commands.argument("target", ResourceLocationArgument.id()).suggests(TARGETS)
                                .executes(AdminCommands::openBookAt)))
                .then(Commands.literal("chapter").requires(src -> src.hasPermission(2))
                        .then(Commands.literal("generate")
                                .then(Commands.literal("creative").executes(AdminCommands::chapterFromCreative))
                                .then(Commands.literal("inventory").executes(AdminCommands::chapterFromInventory))
                                .then(Commands.literal("container")
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(AdminCommands::chapterFromContainer)))))
                .then(Commands.literal("table").requires(src -> src.hasPermission(2))
                        .then(Commands.literal("export")
                                .then(Commands.argument("table", ResourceLocationArgument.id()).suggests(TABLES)
                                        .executes(c -> exportTable(c, null))
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(c -> exportTable(c, BlockPosArgument.getLoadedBlockPos(c, "pos"))))))
                        .then(Commands.literal("import")
                                .then(Commands.argument("name", ResourceLocationArgument.id())
                                        .executes(c -> importTable(c, null))
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(c -> importTable(c, BlockPosArgument.getLoadedBlockPos(c, "pos"))))))
                        .then(Commands.literal("delete_empty").executes(AdminCommands::deleteEmptyTables)))
                .then(Commands.literal("clear_display_cache").requires(src -> src.hasPermission(2))
                        .executes(AdminCommands::clearDisplayCache))
                .then(Commands.literal("cleanup_translations").requires(src -> src.hasPermission(2))
                        .then(Commands.literal("show").executes(c -> cleanupTranslations(c, false)))
                        .then(Commands.literal("execute").executes(c -> cleanupTranslations(c, true)))));
    }

    private static int openBook(CommandContext<CommandSourceStack> c) {
        ServerPlayer player = c.getSource().getPlayer();
        if (player == null) {
            return playerOnly(c);
        }
        GiverService.openDefault(player);
        return 1;
    }

    private static int openBookAt(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            return playerOnly(c);
        }
        String target = ResourceLocationArgument.getId(c, "target").toString();
        if (!GiverService.open(player, target)) {
            src.sendFailure(Component.translatable("commands.kuronami_quest_tree.giver.unknown", target));
            return 0;
        }
        return 1;
    }

    private static int playerOnly(CommandContext<CommandSourceStack> c) {
        c.getSource().sendFailure(Component.translatable("commands.kuronami_quest_tree.giver.player_only"));
        return 0;
    }

    private static int chapterFromCreative(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        CreativeModeTabs.tryRebuildTabContents(src.getServer().getWorldData().enabledFeatures(),
                src.hasPermission(2), src.registryAccess());
        List<String> ids = new ArrayList<>();
        for (CreativeModeTab tab : CreativeModeTabs.tabs()) {
            for (ItemStack stack : tab.getDisplayItems()) {
                addId(ids, stack);
            }
        }
        return createChapter(c, ids);
    }

    private static int chapterFromInventory(CommandContext<CommandSourceStack> c) {
        ServerPlayer player = c.getSource().getPlayer();
        if (player == null) {
            return playerOnly(c);
        }
        List<String> ids = new ArrayList<>();
        for (ItemStack stack : player.getInventory().items) {
            addId(ids, stack);
        }
        return createChapter(c, ids);
    }

    private static int chapterFromContainer(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSourceStack src = c.getSource();
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(c, "pos");
        Container container = HopperBlockEntity.getContainerAt(src.getLevel(), pos);
        if (container == null) {
            src.sendFailure(Component.translatable("commands.kuronami_quest_tree.table.no_container"));
            return 0;
        }
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < container.getContainerSize(); i++) {
            addId(ids, container.getItem(i));
        }
        return createChapter(c, ids);
    }

    private static void addId(List<String> ids, ItemStack stack) {
        if (stack.isEmpty() || stack.is(Items.AIR)) {
            return;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id != null) {
            ids.add(id.toString());
        }
    }

    private static int createChapter(CommandContext<CommandSourceStack> c, List<String> ids) {
        if (ids.isEmpty()) {
            c.getSource().sendFailure(Component.translatable("commands.kuronami_quest_tree.chapter.no_items"));
            return 0;
        }
        JsonObject chapter = ItemChapter.chapter(ids);
        JsonObject args = new JsonObject();
        args.addProperty("namespace", Constants.MOD_ID);
        args.add("chapter", chapter);
        ProgressionEditor.Outcome outcome = EditorService.applyCommand(c.getSource().getServer(),
                writerLevel(c.getSource()), "", 0, "create_chapter", args);
        if (!outcome.ok()) {
            c.getSource().sendFailure(Component.translatable("commands.kuronami_quest_tree.chapter.failed",
                    String.join("; ", outcome.messages())));
            return 0;
        }
        int count = chapter.getAsJsonObject("nodes").size();
        c.getSource().sendSuccess(() -> Component.translatable("commands.kuronami_quest_tree.chapter.generated",
                outcome.createdId(), count), true);
        return count;
    }

    /** FTB Quests' export: every item entry goes into the container, other reward kinds are left out. */
    private static int exportTable(CommandContext<CommandSourceStack> c, BlockPos pos) throws CommandSyntaxException {
        CommandSourceStack src = c.getSource();
        String id = ResourceLocationArgument.getId(c, "table").toString();
        JsonObject table = tables().has(id) ? tables().getAsJsonObject(id) : null;
        if (table == null) {
            src.sendFailure(Component.translatable("commands.kuronami_quest_tree.table.unknown", id));
            return 0;
        }
        Spot spot = containerAt(c, pos);
        if (spot == null) {
            return 0;
        }
        List<ItemStack> stacks = new ArrayList<>();
        for (RewardTableEdit.Item item : RewardTableEdit.items(table)) {
            ResourceLocation itemId = ResourceLocation.tryParse(item.id());
            Item value = itemId == null ? Items.AIR : BuiltInRegistries.ITEM.get(itemId);
            if (value == Items.AIR) {
                continue;
            }
            stacks.add(new ItemStack(value, item.count()));
        }
        putItems(src.getLevel(), spot.container(), spot.pos(), stacks);
        int count = stacks.size();
        src.sendSuccess(() -> Component.translatable("commands.kuronami_quest_tree.table.exported", count, id), false);
        return count;
    }

    /** FTB Quests' import: one entry per non-empty stack, weight 1.0, with the item id and count (no components). */
    private static int importTable(CommandContext<CommandSourceStack> c, BlockPos pos) {
        CommandSourceStack src = c.getSource();
        String id = ResourceLocationArgument.getId(c, "name").toString();
        if (tables().has(id)) {
            src.sendFailure(Component.translatable("commands.kuronami_quest_tree.table.exists", id));
            return 0;
        }
        Spot spot = containerAt(c, pos);
        if (spot == null) {
            return 0;
        }
        List<RewardTableEdit.Item> items = new ArrayList<>();
        for (int i = 0; i < spot.container().getContainerSize(); i++) {
            ItemStack stack = spot.container().getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (itemId != null) {
                items.add(new RewardTableEdit.Item(itemId.toString(), stack.getCount()));
            }
        }
        JsonObject args = new JsonObject();
        args.addProperty("create", true);
        args.addProperty("id", id);
        args.add("table", RewardTableEdit.table(items));
        ProgressionEditor.Outcome outcome = EditorService.applyCommand(src.getServer(), writerLevel(src),
                id, 0, "update_table", args);
        if (!outcome.ok()) {
            src.sendFailure(Component.translatableWithFallback("commands.kuronami_quest_tree.table.failed",
                    "Could not write the table: %s", String.join("; ", outcome.messages())));
            return 0;
        }
        int count = items.size();
        src.sendSuccess(() -> Component.translatable("commands.kuronami_quest_tree.table.imported", id, count), true);
        return count;
    }

    private static int deleteEmptyTables(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        List<String> ids = RewardTableEdit.emptyIds(tables());
        int removed = 0;
        List<String> failed = new ArrayList<>();
        for (String id : ids) {
            ProgressionEditor.Outcome outcome = EditorService.applyCommand(src.getServer(), writerLevel(src),
                    id, EditorService.tableRevision(id), "delete_table", new JsonObject());
            if (outcome.ok()) {
                removed++;
            } else {
                failed.add(id + ": " + String.join("; ", outcome.messages()));
            }
        }
        int count = removed;
        src.sendSuccess(() -> Component.translatable("commands.kuronami_quest_tree.table.delete_empty.done", count), false);
        if (!failed.isEmpty()) {
            src.sendFailure(Component.translatable("commands.kuronami_quest_tree.table.delete_empty.failed",
                    String.join("; ", failed)));
        }
        return removed;
    }

    /**
     * KQT draws items directly and keeps no display cache, so there is nothing to clear. The command exists so a pack
     * author's FTB Quests habit has an answer instead of "unknown command".
     */
    private static int clearDisplayCache(CommandContext<CommandSourceStack> c) {
        c.getSource().sendSuccess(() -> Component.translatable("commands.kuronami_quest_tree.display_cache.none"), false);
        return 0;
    }

    private static int cleanupTranslations(CommandContext<CommandSourceStack> c, boolean execute) {
        CommandSourceStack src = c.getSource();
        JsonObject bundle = ProgressData.mergedBundle();
        Map<String, JsonObject> langs = ProgressData.langs();
        Map<String, List<String>> found = new TreeMap<>();
        int total = 0;
        for (Map.Entry<String, JsonObject> e : langs.entrySet()) {
            List<String> stale = TranslationCleanup.stale(bundle, e.getValue());
            if (!stale.isEmpty()) {
                found.put(e.getKey(), stale);
                total += stale.size();
            }
        }
        if (total == 0) {
            src.sendSuccess(() -> Component.translatable("commands.kuronami_quest_tree.translations.none"), false);
            return 0;
        }
        if (!execute) {
            int count = total;
            src.sendSuccess(() -> Component.translatable("commands.kuronami_quest_tree.translations.header", count), false);
            for (Map.Entry<String, List<String>> e : found.entrySet()) {
                int size = e.getValue().size();
                src.sendSuccess(() -> Component.translatable("commands.kuronami_quest_tree.translations.locale",
                        e.getKey(), size), false);
                for (String key : e.getValue()) {
                    src.sendSuccess(() -> Component.translatable("commands.kuronami_quest_tree.translations.key", key), false);
                }
            }
            return total;
        }
        // only the world's data packs are writable: a pack from a mod's jar or config keeps its keys and is reported
        int[] written = new int[2];
        List<String> seen = new ArrayList<>();
        Path datapacks = src.getServer().getWorldPath(LevelResource.DATAPACK_DIR);
        if (Files.isDirectory(datapacks)) {
            try (Stream<Path> walk = Files.walk(datapacks)) {
                for (Path file : walk.filter(AdminCommands::isLangFile).toList()) {
                    JsonObject locale;
                    try {
                        locale = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                    } catch (RuntimeException | IOException ex) {
                        Constants.LOG.warn("Skipping unreadable translation file {}", file, ex);
                        continue;
                    }
                    List<String> stale = TranslationCleanup.stale(bundle, locale);
                    if (stale.isEmpty()) {
                        continue;
                    }
                    int removed = TranslationCleanup.remove(locale, stale);
                    if (removed > 0) {
                        Files.writeString(file, PRETTY.toJson(locale) + "\n", StandardCharsets.UTF_8);
                        written[0] += removed;
                        written[1]++;
                        for (String key : stale) {
                            seen.add(localeKey(file, key));
                        }
                    }
                }
            } catch (IOException ex) {
                src.sendFailure(Component.translatableWithFallback("commands.kuronami_quest_tree.translations.failed",
                        "Could not clean translations: %s", ex.getMessage()));
                return 0;
            }
        }
        int removed = written[0];
        int files = written[1];
        int remaining = Math.max(0, total - (int) seen.stream().distinct().count());
        src.sendSuccess(() -> Component.translatable("commands.kuronami_quest_tree.translations.removed", removed, files), false);
        if (remaining > 0) {
            src.sendSuccess(() -> Component.translatable("commands.kuronami_quest_tree.translations.remaining", remaining), false);
        }
        return removed;
    }

    /** {@code .../data/<ns>/progression/lang/<locale>.json}. */
    private static boolean isLangFile(Path file) {
        String normalized = file.toString().replace('\\', '/');
        return normalized.contains("/progression/lang/") && normalized.endsWith(".json");
    }

    private static String localeKey(Path file, String key) {
        return file.getFileName().toString() + "|" + key;
    }

    private static void putItems(ServerLevel level, Container container, BlockPos pos, List<ItemStack> stacks) {
        int slot = 0;
        int size = container.getContainerSize();
        for (ItemStack stack : stacks) {
            int remaining = stack.getCount();
            while (remaining > 0 && slot < size) {
                if (!container.getItem(slot).isEmpty()) {
                    slot++;
                    continue;
                }
                int moved = Math.min(remaining, stack.getMaxStackSize());
                container.setItem(slot, stack.copyWithCount(moved));
                remaining -= moved;
                slot++;
            }
            if (remaining > 0) {
                Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5,
                        stack.copyWithCount(remaining));
            }
        }
        container.setChanged();
        level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
    }

    /** A container and the block it sits at, so a dropped overflow lands on the same block. */
    private record Spot(Container container, BlockPos pos) {
    }

    /** The container at the position, or the one the player is looking at when no position was given. */
    @SuppressWarnings("deprecation")
    private static Spot containerAt(CommandContext<CommandSourceStack> c, BlockPos pos) {
        CommandSourceStack src = c.getSource();
        BlockPos at = pos;
        if (at == null) {
            ServerPlayer player = src.getPlayer();
            if (player == null) {
                playerOnly(c);
                return null;
            }
            var hit = player.pick(10.0, 1.0F, false);
            if (!(hit instanceof net.minecraft.world.phys.BlockHitResult block)) {
                src.sendFailure(Component.translatable("commands.kuronami_quest_tree.table.no_container"));
                return null;
            }
            at = block.getBlockPos();
        }
        Container container = HopperBlockEntity.getContainerAt(src.getLevel(), at);
        if (container == null) {
            src.sendFailure(Component.translatable("commands.kuronami_quest_tree.table.no_container"));
            return null;
        }
        return new Spot(container, at);
    }

    private static JsonObject tables() {
        JsonObject bundle = ProgressData.mergedBundle();
        return bundle.has("reward_tables") && bundle.get("reward_tables").isJsonObject()
                ? bundle.getAsJsonObject("reward_tables") : new JsonObject();
    }

    private static int writerLevel(CommandSourceStack src) {
        for (int level = 4; level > 0; level--) {
            if (src.hasPermission(level)) {
                return level;
            }
        }
        return 0;
    }
}
