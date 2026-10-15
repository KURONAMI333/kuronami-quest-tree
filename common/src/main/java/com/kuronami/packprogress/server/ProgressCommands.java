package com.kuronami.packprogress.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.kuronami.packprogress.Constants;
import com.kuronami.packprogress.importer.FtbQuestsImporter;
import com.kuronami.packprogress.importer.ImportOutput;
import com.kuronami.packprogress.importer.PuffishImporter;
import com.kuronami.packprogress.platform.Services;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * {@code /pack_progress import ftbquests|puffish [namespace]}: converts the installed quest book / skill trees into a
 * data pack inside the world ({@code datapacks/pack_progress_import}) and reloads, so a pack author can switch over in
 * one step. The original files are only read, never changed.
 */
public final class ProgressCommands {

    private static final String PACK_DIR = "pack_progress_import";

    private ProgressCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(Constants.MOD_ID)
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("import")
                        .then(Commands.literal("ftbquests")
                                .executes(c -> importFtb(c, "ftbquests"))
                                .then(Commands.argument("namespace", StringArgumentType.word())
                                        .executes(c -> importFtb(c, StringArgumentType.getString(c, "namespace")))))
                        .then(Commands.literal("puffish")
                                .executes(c -> importPuffish(c, "skills"))
                                .then(Commands.argument("namespace", StringArgumentType.word())
                                        .executes(c -> importPuffish(c, StringArgumentType.getString(c, "namespace"))))))
                .then(Commands.literal("status").executes(c -> {
                    c.getSource().sendSuccess(() -> Component.literal(ProgressData.chapterCount() + " chapters loaded"), false);
                    return ProgressData.chapterCount();
                })));
    }

    private static int importFtb(CommandContext<CommandSourceStack> c, String ns) {
        Path game = Services.PLATFORM.getGameDir();
        Path quests = Stream.of("config/ftbquests/quests", "defaultconfigs/ftbquests/quests")
                .map(game::resolve).filter(Files::isDirectory).findFirst().orElse(null);
        if (quests == null) {
            c.getSource().sendFailure(Component.translatable("commands.pack_progress.import.ftbquests.missing"));
            return 0;
        }
        ImportOutput out = new ImportOutput(validNamespace(ns));
        try {
            FtbQuestsImporter.convert(quests, out);
        } catch (Exception ex) {
            Constants.LOG.error("FTB Quests import failed", ex);
            c.getSource().sendFailure(Component.translatable("commands.pack_progress.import.failed", ex.getMessage()));
            return 0;
        }
        return finish(c, out);
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
            c.getSource().sendFailure(Component.translatable("commands.pack_progress.import.puffish.missing"));
            return 0;
        }
        ImportOutput out = new ImportOutput(validNamespace(ns));
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
            src.sendFailure(Component.translatable("commands.pack_progress.import.failed", ex.getMessage()));
            return 0;
        }
        int nodes = out.counts.getOrDefault("nodes", 0) + out.counts.getOrDefault("skills", 0);
        int chapters = out.counts.getOrDefault("chapters", 0) + out.counts.getOrDefault("skill_categories", 0);
        src.sendSuccess(() -> Component.translatable("commands.pack_progress.import.done", chapters, nodes, out.namespace), true);
        if (!out.unmapped.isEmpty()) {
            src.sendSuccess(() -> Component.translatable("commands.pack_progress.import.unmapped", out.unmapped.size())
                    .withStyle(ChatFormatting.YELLOW), false);
            out.unmapped.forEach((k, n) -> src.sendSuccess(() -> Component.literal("  " + k + " x" + n).withStyle(ChatFormatting.GRAY), false));
        }
        // newly written data packs are picked up and enabled by /reload
        src.getServer().getCommands().performPrefixedCommand(src, "reload");
        return Math.max(1, nodes);
    }

    /** Namespaces become folder names; only [a-z0-9_] so "." or ".." can never point outside the data pack. */
    static String validNamespace(String ns) {
        String s = ns.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        return s.isEmpty() || s.chars().allMatch(c -> c == '_') ? "imported" : s;
    }
}
