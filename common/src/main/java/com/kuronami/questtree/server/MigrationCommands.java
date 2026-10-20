package com.kuronami.questtree.server;

import com.google.gson.JsonObject;
import com.kuronami.questtree.Constants;
import com.kuronami.questtree.data.ProgressModel;
import com.kuronami.questtree.importer.Snbt;
import com.kuronami.questtree.migration.FtbTeamsMembers;
import com.kuronami.questtree.migration.FtbqBook;
import com.kuronami.questtree.migration.FtbqMigration;
import com.kuronami.questtree.migration.FtbqTeamData;
import com.kuronami.questtree.migration.MigrationHash;
import com.kuronami.questtree.migration.MigrationReport;
import com.kuronami.questtree.migration.NbtJson;
import com.kuronami.questtree.migration.PuffishMigration;
import com.kuronami.questtree.progress.TeamStoreData;
import com.kuronami.questtree.team.TeamResolver;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * {@code /quest_tree migrate ftbquests [namespace] [dry_run|apply]} and {@code migrate puffish [namespace]
 * [dry_run|apply]} (2026-10-20): moves existing FTB Quests team progress and Pufferfish's Skills player data into KQT's
 * team records. Operators at level 4 and the server console only, because an apply rewrites everybody's progress.
 * <p>
 * The mapping lives in {@code com.kuronami.questtree.migration} and runs on a copy of the store, so a dry run and an
 * apply produce the same report and a failure leaves the real store untouched. An apply first saves the world's data,
 * copies the store file to {@code data/kuronami_quest_tree.dat.migrate-<time>.bak}, then swaps the merged copy in. The
 * report goes to chat (first 20 unresolved ids) and, in full, to {@code <world>/kuronami_quest_tree/migration-<time>.txt}.
 */
final class MigrationCommands {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private MigrationCommands() {
    }

    /** What a run does: look only, write, or write even though the same source was applied before. */
    private enum Mode { DRY_RUN, APPLY, FORCE }

    private interface Run {
        int run(CommandContext<CommandSourceStack> c, String namespace, Mode mode);
    }

    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("migrate").requires(s -> s.hasPermission(4))
                .then(source("ftbquests", "ftbquests", MigrationCommands::ftbquests))
                .then(source("puffish", null, MigrationCommands::puffish));
    }

    /** {@code <source> [namespace] [dry_run | apply [force]]}; a literal wins over the namespace word. */
    private static LiteralArgumentBuilder<CommandSourceStack> source(String name, String defaultNamespace, Run run) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(name);
        root.executes(c -> run.run(c, defaultNamespace, Mode.DRY_RUN));
        root.then(Commands.literal("dry_run").executes(c -> run.run(c, defaultNamespace, Mode.DRY_RUN)));
        root.then(Commands.literal("apply").executes(c -> run.run(c, defaultNamespace, Mode.APPLY))
                .then(Commands.literal("force").executes(c -> run.run(c, defaultNamespace, Mode.FORCE))));
        root.then(Commands.argument("namespace", StringArgumentType.word())
                .executes(c -> run.run(c, StringArgumentType.getString(c, "namespace"), Mode.DRY_RUN))
                .then(Commands.literal("dry_run")
                        .executes(c -> run.run(c, StringArgumentType.getString(c, "namespace"), Mode.DRY_RUN)))
                .then(Commands.literal("apply")
                        .executes(c -> run.run(c, StringArgumentType.getString(c, "namespace"), Mode.APPLY))
                        .then(Commands.literal("force")
                                .executes(c -> run.run(c, StringArgumentType.getString(c, "namespace"), Mode.FORCE)))));
        return root;
    }

    // ------------------------------------------------------------------ ftbquests

    private static int ftbquests(CommandContext<CommandSourceStack> c, String requested, Mode mode) {
        CommandSourceStack src = c.getSource();
        String ns = ProgressCommands.validNamespace(src, requested);
        if (ns == null) {
            return 0;
        }
        MinecraftServer server = src.getServer();
        Path world = server.getWorldPath(LevelResource.ROOT);
        Path progressDir = world.resolve("ftbquests");
        if (!Files.isDirectory(progressDir)) {
            return fail(src, "commands.kuronami_quest_tree.migrate.ftbquests.no_progress", world.relativize(progressDir).toString());
        }
        Path questsDir = ProgressCommands.ftbQuestsDir();
        if (questsDir == null) {
            return fail(src, "commands.kuronami_quest_tree.import.ftbquests.missing");
        }
        ProgressModel model = ProgressData.model();
        if (model.chapters.values().stream().noneMatch(ch -> ch.namespace.equals(ns) && !ch.isSkills())) {
            return fail(src, "commands.kuronami_quest_tree.migrate.no_book", ns);
        }
        MigrationReport report = new MigrationReport();
        try {
            FtbqBook book = FtbqBook.read(questsDir);
            List<FtbqTeamData> teams = new ArrayList<>();
            try (Stream<Path> list = Files.list(progressDir)) {
                for (Path f : list.filter(p -> p.getFileName().toString().contains("-")
                        && p.getFileName().toString().endsWith(".snbt")).sorted().toList()) {
                    try {
                        teams.add(FtbqTeamData.parse(Snbt.parse(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject()));
                    } catch (RuntimeException ex) {
                        report.unresolved("unreadable_file", f.getFileName().toString());
                    }
                }
            }
            FtbTeamsMembers membership = null;
            Path ftbteams = world.resolve("ftbteams");
            if (Files.isDirectory(ftbteams)) {
                int[] unreadable = {0};
                membership = FtbTeamsMembers.read(ftbteams, unreadable);
                report.add("ftb_teams_files_unreadable", unreadable[0]);
                if (membership.serverTeams() > 0) {
                    report.note(membership.serverTeams() + " FTB Teams server team(s): members taken from their ranks, not confirmed");
                }
            } else {
                report.note("no ftbteams folder: every FTB Quests team is treated as one player");
            }
            ProgressStore store = ProgressStore.get(server);
            TeamStoreData copy = store.copyData();
            FtbqMigration.run(new FtbqMigration.Input(ns, model, book, teams, membership,
                    player -> TeamResolver.teamId(server, player)), copy, report);
            List<Path> inputs = new ArrayList<>();
            try (Stream<Path> list = Files.list(progressDir)) {
                list.filter(p -> p.getFileName().toString().contains("-") && p.getFileName().toString().endsWith(".snbt"))
                        .sorted().forEach(inputs::add);
            }
            return finish(src, store, copy, report, "FTB Quests", "ftbquests:" + ns, MigrationHash.of(inputs), mode);
        } catch (IOException | RuntimeException ex) {
            Constants.LOG.error("FTB Quests progress migration failed", ex);
            return fail(src, "commands.kuronami_quest_tree.migrate.failed", String.valueOf(ex.getMessage()));
        }
    }

    // ------------------------------------------------------------------ puffish

    private static int puffish(CommandContext<CommandSourceStack> c, String requested, Mode mode) {
        CommandSourceStack src = c.getSource();
        String ns = null;
        if (requested != null) {
            ns = ProgressCommands.validNamespace(src, requested);
            if (ns == null) {
                return 0;
            }
        }
        MinecraftServer server = src.getServer();
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("puffish_skills.dat");
        if (!Files.isRegularFile(file)) {
            return fail(src, "commands.kuronami_quest_tree.migrate.puffish.no_data", "data/puffish_skills.dat");
        }
        ProgressModel model = ProgressData.model();
        if (model.chapters.values().stream().noneMatch(ch -> ch.isSkills())) {
            return fail(src, "commands.kuronami_quest_tree.migrate.no_book", ns == null ? "*" : ns);
        }
        MigrationReport report = new MigrationReport();
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            JsonObject players = NbtJson.of(root.getCompound("data").getCompound("players"));
            Map<UUID, JsonObject> byPlayer = new LinkedHashMap<>();
            players.entrySet().forEach(e -> {
                try {
                    byPlayer.put(UUID.fromString(e.getKey()), e.getValue().getAsJsonObject());
                } catch (RuntimeException ex) {
                    report.unresolved("player", e.getKey());
                }
            });
            ProgressStore store = ProgressStore.get(server);
            TeamStoreData copy = store.copyData();
            PuffishMigration.run(new PuffishMigration.Input(ns, model, byPlayer,
                    player -> TeamResolver.teamId(server, player)), copy, report);
            return finish(src, store, copy, report, "Pufferfish's Skills", "puffish:" + (ns == null ? "*" : ns),
                    MigrationHash.of(List.of(file)), mode);
        } catch (IOException | RuntimeException ex) {
            Constants.LOG.error("Pufferfish's Skills progress migration failed", ex);
            return fail(src, "commands.kuronami_quest_tree.migrate.failed", String.valueOf(ex.getMessage()));
        }
    }

    // ------------------------------------------------------------------ shared

    private static int finish(CommandSourceStack src, ProgressStore store, TeamStoreData merged, MigrationReport report,
                              String title, String source, String hash, Mode mode) throws IOException {
        boolean apply = mode != Mode.DRY_RUN;
        String before = merged.migrations.get(source);
        if (apply && before != null && mode != Mode.FORCE) {
            // a second apply after people have played can bring back repeatable state that was cleared since
            return fail(src, "commands.kuronami_quest_tree.migrate.already_applied", source, before);
        }
        if (before != null) {
            report.note("already applied: " + source + " " + before);
        }
        MinecraftServer server = src.getServer();
        Path world = server.getWorldPath(LevelResource.ROOT);
        String stamp = LocalDateTime.now().format(STAMP);
        Path backup = null;
        if (apply) {
            // write what is in memory first, so the copy is the state the merge starts from
            server.overworld().getDataStorage().save();
            Path storeFile = world.resolve("data").resolve(Constants.MOD_ID + ".dat");
            if (Files.isRegularFile(storeFile)) {
                backup = storeFile.resolveSibling(Constants.MOD_ID + ".dat.migrate-" + stamp + ".bak");
                Files.copy(storeFile, backup, StandardCopyOption.COPY_ATTRIBUTES);
            }
            merged.migrations.put(source, hash + "@" + stamp);
            store.replaceData(merged);
            server.overworld().getDataStorage().save();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                Rewards.refreshAttributes(player, ProgressService.progress(player));
                ProgressService.markDirty(player);
                ProgressService.checkNow(player);
            }
        }
        Path reportFile = world.resolve("kuronami_quest_tree").resolve("migration-" + stamp + ".txt");
        Files.createDirectories(reportFile.getParent());
        Files.writeString(reportFile, report.toText(title, apply, stamp), StandardCharsets.UTF_8);
        Constants.LOG.info("{} progress migration ({}): {}", title, apply ? "apply" : "dry_run", report.counts());

        src.sendSuccess(() -> Component.translatable("commands.kuronami_quest_tree.migrate.header", title,
                Component.translatable("commands.kuronami_quest_tree.migrate.mode." + (apply ? "apply" : "dry_run"))), true);
        for (Map.Entry<String, Integer> e : report.counts().entrySet()) {
            src.sendSuccess(() -> Component.translatable("commands.kuronami_quest_tree.migrate.count",
                    Component.translatableWithFallback("commands.kuronami_quest_tree.migrate.label." + e.getKey(), e.getKey()),
                    e.getValue()), false);
        }
        if (report.get("unresolved") == 0) {
            src.sendSuccess(() -> Component.translatable("commands.kuronami_quest_tree.migrate.label.unresolved")
                    .append(": 0"), false);
        }
        List<String> unresolved = report.unresolvedList();
        int shown = Math.min(MigrationReport.CHAT_UNRESOLVED, unresolved.size());
        for (int i = 0; i < shown; i++) {
            String line = unresolved.get(i);
            src.sendSuccess(() -> Component.literal("  " + line).withStyle(ChatFormatting.YELLOW), false);
        }
        if (unresolved.size() > shown) {
            int more = unresolved.size() - shown;
            src.sendSuccess(() -> Component.translatable("commands.kuronami_quest_tree.migrate.more", more), false);
        }
        if (backup != null) {
            Path b = backup;
            src.sendSuccess(() -> Component.translatable("commands.kuronami_quest_tree.migrate.backup", b.getFileName().toString()), false);
        }
        src.sendSuccess(() -> Component.translatable("commands.kuronami_quest_tree.migrate.report",
                world.relativize(reportFile).toString().replace('\\', '/')), false);
        return Math.max(1, report.counts().values().stream().mapToInt(Integer::intValue).sum());
    }

    private static int fail(CommandSourceStack src, String key, Object... args) {
        src.sendFailure(Component.translatable(key, args));
        return 0;
    }
}
