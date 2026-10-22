package com.kuronami.questtree.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuronami.questtree.Constants;
import com.kuronami.questtree.importer.FtbQuestsImporter;
import com.kuronami.questtree.importer.ImportOutput;
import com.kuronami.questtree.importer.PuffishImporter;
import com.kuronami.questtree.platform.Services;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * {@code /quest_tree import ftbquests|puffish [namespace]}: converts the installed quest book / skill trees into a
 * data pack inside the world ({@code datapacks/quest_tree_import}) and reloads, so a pack author can switch over in
 * one step. The original files are only read, never changed.
 */
public final class ProgressCommands {

    private static final String PACK_DIR = "quest_tree_import";
    private static final java.util.function.Predicate<CommandSourceStack> ADMIN = s -> s.hasPermission(2);

    private ProgressCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(Constants.COMMAND)
                // party commands are for every player; the book/admin commands below keep the old permission gate
                .then(PartyCommands.node())
                .then(MigrationCommands.node())
                .then(GiverCommands.node())
                .then(Commands.literal("import").requires(ADMIN)
                        .then(Commands.literal("ftbquests")
                                .executes(c -> importFtb(c, "ftbquests"))
                                .then(Commands.argument("namespace", StringArgumentType.word())
                                        .executes(c -> importFtb(c, StringArgumentType.getString(c, "namespace")))))
                        .then(Commands.literal("puffish")
                                .executes(c -> importPuffish(c, "skills"))
                                .then(Commands.argument("namespace", StringArgumentType.word())
                                        .executes(c -> importPuffish(c, StringArgumentType.getString(c, "namespace"))))))
                .then(Commands.literal("status").requires(ADMIN).executes(c -> {
                    c.getSource().sendSuccess(() -> Component.literal(ProgressData.chapterCount() + " chapters loaded"), false);
                    return ProgressData.chapterCount();
                }))
                .then(Commands.literal("validate").requires(ADMIN).executes(ProgressCommands::validate))
                .then(Commands.literal("edit").requires(ADMIN)
                        .executes(c -> edit(c, null))
                        .then(Commands.literal("on").executes(c -> edit(c, true)))
                        .then(Commands.literal("off").executes(c -> edit(c, false))))
                .then(Commands.literal("export").requires(ADMIN)
                        .then(Commands.argument("namespace", StringArgumentType.word())
                                .executes(c -> export(c, StringArgumentType.getString(c, "namespace"), false))
                                .then(Commands.literal("force")
                                        .executes(c -> export(c, StringArgumentType.getString(c, "namespace"), true))))));
        PlayerCommands.register(dispatcher);
        AdminCommands.register(dispatcher);
    }

    /** How many of a chapter's problems {@code /quest_tree validate} prints before pointing at the report. */
    private static final int VALIDATE_LINES_PER_GROUP = 5;

    /**
     * Reports what the pack validation found wrong (ERRORs drop the node; see DATA_MODEL.md "Validation"). Runs the
     * post-start pass first, so tags and the loaded world's registries are answered, then prints counts and the first
     * few problems of each chapter. The full grouped list is in the log and in the world report.
     */
    private static int validate(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        // the command is the on-demand way to see the deeper findings without waiting for the next tick
        ProgressData.refreshAchievability(src.getServer());
        List<ProgressValidator.Problem> problems = new ArrayList<>(ProgressData.problems());
        int errors = ProgressData.errorCount();
        int warnings = ProgressData.warningCount();
        if (problems.isEmpty()) {
            src.sendSuccess(() -> Component.translatable("commands.kuronami_quest_tree.validate.none"), false);
            return 1;
        }
        src.sendSuccess(() -> Component.translatable("commands.kuronami_quest_tree.validate.summary", errors, warnings), false);
        for (Map.Entry<String, List<ProgressValidator.Problem>> e : ProgressData.groups(problems).entrySet()) {
            List<ProgressValidator.Problem> ps = e.getValue();
            int groupErrors = 0;
            for (ProgressValidator.Problem p : ps) {
                if (p.severity() == ProgressValidator.Severity.ERROR) {
                    groupErrors++;
                }
            }
            int groupErrorsFinal = groupErrors;
            int groupWarnings = ps.size() - groupErrors;
            src.sendSuccess(() -> Component.translatable("commands.kuronami_quest_tree.validate.group",
                    e.getKey(), groupErrorsFinal, groupWarnings), false);
            int shown = Math.min(VALIDATE_LINES_PER_GROUP, ps.size());
            for (int i = 0; i < shown; i++) {
                String line = ps.get(i).format();
                src.sendSuccess(() -> Component.translatable("commands.kuronami_quest_tree.validate.problem", line), false);
            }
            if (ps.size() > shown) {
                src.sendSuccess(() -> Component.translatable("commands.kuronami_quest_tree.validate.more", ps.size() - shown), false);
            }
        }
        var report = ProgressData.lastReport();
        if (report != null) {
            src.sendSuccess(() -> Component.translatable("commands.kuronami_quest_tree.validate.report",
                    report.getFileName().toString()), false);
        }
        return Math.max(0, errors);
    }

    /** Turns editing mode on/off for this player; without an argument it toggles. */
    private static int edit(CommandContext<CommandSourceStack> c, Boolean on) {
        CommandSourceStack src = c.getSource();
        net.minecraft.server.level.ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.translatableWithFallback("commands.kuronami_quest_tree.edit.player_only",
                    "Only a player can turn editing mode on or off"));
            return 0;
        }
        boolean state = on == null ? !EditorService.isEditing(player) : on;
        EditorService.setEditing(player, state);
        src.sendSuccess(() -> Component.translatableWithFallback(
                state ? "commands.kuronami_quest_tree.edit.on" : "commands.kuronami_quest_tree.edit.off",
                state ? "Quest editing is on" : "Quest editing is off"), false);
        return state ? 1 : 0;
    }

    /**
     * Writes the validated book as a data pack for one namespace, without enabling it or reloading. This is the way
     * out of the world overlay: what the editor accepted becomes ordinary files an author can keep in a mod or pack.
     * An existing folder is only replaced when the caller adds {@code force}, because {@link ImportOutput#writeTo}
     * deletes the namespace's previous progression files first.
     */
    private static int export(CommandContext<CommandSourceStack> c, String requested, boolean force) {
        CommandSourceStack src = c.getSource();
        String ns = ProgressExport.validNamespace(requested);
        if (ns == null) {
            src.sendFailure(Component.translatableWithFallback("commands.kuronami_quest_tree.export.namespace",
                    "%s is not a valid resource namespace", requested));
            return 0;
        }
        ImportOutput out = ProgressExport.collect(ns, ProgressData.mergedBundle(), ProgressData.langs(),
                ProgressData.droppedNodes());
        int nodeCount = out.counts.getOrDefault("nodes", 0);
        int dropped = out.counts.getOrDefault("dropped_nodes", 0);
        Path root = src.getServer().getWorldPath(LevelResource.DATAPACK_DIR).resolve(ProgressExport.packFolder(ns));
        try {
            ProgressExport.write(out, root, force);
        } catch (java.nio.file.FileAlreadyExistsException ex) {
            src.sendFailure(Component.translatableWithFallback("commands.kuronami_quest_tree.export.exists",
                    "%s already exists; add force to replace it", root.getFileName()));
            return 0;
        } catch (IOException ex) {
            Constants.LOG.error("Exporting progression data failed", ex);
            src.sendFailure(Component.translatableWithFallback("commands.kuronami_quest_tree.export.failed",
                    "Export failed: %s", ex.getMessage()));
            return 0;
        }
        src.sendSuccess(() -> Component.translatableWithFallback("commands.kuronami_quest_tree.export.done",
                "Exported %s chapters with %s nodes and %s translations to %s (not enabled; review it before turning it on)",
                out.chapters.size(), nodeCount, out.counts.getOrDefault("lang", 0), root.getFileName()), true);
        if (dropped > 0) {
            int count = dropped;
            src.sendSuccess(() -> Component.translatableWithFallback("commands.kuronami_quest_tree.export.dropped",
                    "%s nodes were left out because the validator marked them as ERROR", count)
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        return Math.max(1, nodeCount);
    }

    private static int importFtb(CommandContext<CommandSourceStack> c, String ns) {
        Path quests = ftbQuestsDir();
        if (quests == null) {
            c.getSource().sendFailure(Component.translatableWithFallback("commands.kuronami_quest_tree.import.ftbquests.missing", "No FTB Quests data found in config/ftbquests/quests"));
            return 0;
        }
        String namespace = validNamespace(c.getSource(), ns);
        if (namespace == null) {
            return 0;
        }
        ImportOutput out = new ImportOutput(namespace);
        try {
            FtbQuestsImporter.convert(quests, out);
        } catch (Exception ex) {
            Constants.LOG.error("FTB Quests import failed", ex);
            c.getSource().sendFailure(Component.translatableWithFallback("commands.kuronami_quest_tree.import.failed", "Import failed: %s", ex.getMessage()));
            return 0;
        }
        return finish(c, out);
    }

    /** The FTB Quests quest folder the import reads, also used to find the ids a progress migration needs. */
    static Path ftbQuestsDir() {
        Path game = Services.PLATFORM.getGameDir();
        return Stream.of("config/ftbquests/quests", "defaultconfigs/ftbquests/quests")
                .map(game::resolve).filter(Files::isDirectory).findFirst().orElse(null);
    }

    private static int importPuffish(CommandContext<CommandSourceStack> c, String ns) {
        MinecraftServer server = c.getSource().getServer();
        // categories from data packs (and mods that ship trees) ...
        Map<String, Map<String, JsonElement>> cats = new TreeMap<>();
        for (Map.Entry<ResourceLocation, Resource> e : server.getResourceManager()
                .listResources("puffish_skills/categories", id -> id.getPath().endsWith(".json")).entrySet()) {
            String path = e.getKey().getPath().substring("puffish_skills/categories/".length());
            int slash = path.indexOf('/');
            if (slash < 0) {
                continue;
            }
            String key = e.getKey().getNamespace() + "/" + path.substring(0, slash);
            try (Reader r = e.getValue().openAsReader()) {
                cats.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(path.substring(slash + 1), JsonParser.parseReader(r));
            } catch (Exception ex) {
                Constants.LOG.warn("Skipping unreadable {}: {}", e.getKey(), ex.getMessage());
            }
        }
        // ... and from the config folder (older packs such as Prominence II keep trees there)
        Path cfg = Services.PLATFORM.getGameDir().resolve("config/puffish_skills/categories");
        if (Files.isDirectory(cfg)) {
            try (Stream<Path> dirs = Files.list(cfg)) {
                for (Path d : dirs.filter(Files::isDirectory).toList()) {
                    Map<String, JsonElement> files = cats.computeIfAbsent("config/" + d.getFileName(), k -> new LinkedHashMap<>());
                    try (Stream<Path> fs = Files.list(d)) {
                        for (Path f : fs.filter(p -> p.toString().endsWith(".json")).toList()) {
                            files.put(f.getFileName().toString(), JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)));
                        }
                    }
                }
            } catch (IOException ex) {
                Constants.LOG.warn("Could not read {}: {}", cfg, ex.getMessage());
            }
        }
        if (cats.isEmpty()) {
            c.getSource().sendFailure(Component.translatableWithFallback("commands.kuronami_quest_tree.import.puffish.missing", "No Pufferfish's Skills categories found"));
            return 0;
        }
        String namespace = validNamespace(c.getSource(), ns);
        if (namespace == null) {
            return 0;
        }
        ImportOutput out = new ImportOutput(namespace);
        cats.forEach((k, files) -> PuffishImporter.convert(k, files, out));
        return finish(c, out);
    }

    private static int finish(CommandContext<CommandSourceStack> c, ImportOutput out) {
        CommandSourceStack src = c.getSource();
        Path root = src.getServer().getWorldPath(LevelResource.DATAPACK_DIR).resolve(PACK_DIR);
        try {
            out.writeTo(root);
        } catch (IOException ex) {
            Constants.LOG.error("Writing imported data failed", ex);
            src.sendFailure(Component.translatableWithFallback("commands.kuronami_quest_tree.import.failed", "Import failed: %s", ex.getMessage()));
            return 0;
        }
        int nodes = out.counts.getOrDefault("nodes", 0) + out.counts.getOrDefault("skills", 0);
        int chapters = out.counts.getOrDefault("chapters", 0) + out.counts.getOrDefault("skill_categories", 0);
        src.sendSuccess(() -> Component.translatableWithFallback("commands.kuronami_quest_tree.import.done", "Imported %s chapters with %s nodes into the data pack \"quest_tree_import\" (namespace %s). Reloading.", chapters, nodes, out.namespace), true);
        if (!out.unmapped.isEmpty()) {
            src.sendSuccess(() -> Component.translatableWithFallback("commands.kuronami_quest_tree.import.unmapped", "%s settings could not be carried over:", out.unmapped.size())
                    .withStyle(ChatFormatting.YELLOW), false);
            // the full list is in import_report_<ns>.json; chat shows the most frequent few
            out.unmapped.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(8)
                    .forEach(e -> src.sendSuccess(() -> Component.literal("  " + e.getKey() + " x" + e.getValue()).withStyle(ChatFormatting.GRAY), false));
        }
        // newly written data packs are picked up and enabled by /reload
        src.getServer().getCommands().performPrefixedCommand(src, "reload");
        return Math.max(1, nodes);
    }

    /** The import commands' namespace; an invalid one is reported instead of being silently rewritten. */
    static String validNamespace(CommandSourceStack src, String requested) {
        String ns = ProgressExport.validNamespace(requested);
        if (ns == null) {
            src.sendFailure(Component.translatableWithFallback("commands.kuronami_quest_tree.import.namespace",
                    "%s is not a valid resource namespace", requested));
        }
        return ns;
    }
}
