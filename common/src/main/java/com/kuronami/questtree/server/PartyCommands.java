package com.kuronami.questtree.server;

import com.kuronami.questtree.Constants;
import com.kuronami.questtree.team.PartyData;
import com.kuronami.questtree.team.PartyStore;
import com.kuronami.questtree.team.TeamResolver;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.UUID;

/**
 * {@code /quest_tree party ...}: KQT's built-in party for packs without FTB Teams. Progress is shared by the party
 * the same way FTB Teams parties share it; every member keeps their own claims. When FTB Teams is installed its
 * parties are the only ones used, and these actions point at {@code /ftbteams party} instead of creating a second
 * membership model.
 * <pre>
 * /quest_tree party create            make a party (you are the owner)
 * /quest_tree party invite &lt;player&gt;   invite someone (valid for 5 minutes)
 * /quest_tree party accept            join the party you were invited to
 * /quest_tree party leave             leave (ownership passes to the oldest remaining member)
 * /quest_tree party kick &lt;player&gt;     owner only
 * /quest_tree party disband           owner only
 * /quest_tree party info              members and current invites
 * </pre>
 */
final class PartyCommands {

    private PartyCommands() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("party")
                .then(Commands.literal("create").executes(PartyCommands::create))
                .then(Commands.literal("invite")
                        .then(Commands.argument("player", EntityArgument.player())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                        c.getSource().getServer().getPlayerList().getPlayers().stream()
                                                .map(p -> p.getGameProfile().getName()), b))
                                .executes(PartyCommands::invite)))
                .then(Commands.literal("accept").executes(PartyCommands::accept))
                .then(Commands.literal("leave").executes(PartyCommands::leave))
                .then(Commands.literal("kick")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(rosterNames(c.getSource()), b))
                                .executes(PartyCommands::kick)))
                .then(Commands.literal("disband").executes(PartyCommands::disband))
                .then(Commands.literal("info").executes(PartyCommands::info));
    }

    /** True (and answers) when FTB Teams owns parties, so KQT must not create a second membership. */
    private static boolean managedElsewhere(CommandSourceStack src) {
        if (!TeamResolver.managedByFtbTeams()) {
            return false;
        }
        src.sendFailure(Component.translatableWithFallback("commands.kuronami_quest_tree.party.ftb",
                "Parties are managed by FTB Teams. Use /ftbteams party instead."));
        return true;
    }

    private static PartyData data(CommandSourceStack src) {
        return PartyStore.get(src.getServer()).data();
    }

    private static void dirty(CommandSourceStack src) {
        PartyStore.get(src.getServer()).setDirty();
    }

    private static int create(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSourceStack src = c.getSource();
        if (managedElsewhere(src)) {
            return 0;
        }
        ServerPlayer player = src.getPlayerOrException();
        PartyData.Party party = data(src).create(player.getUUID(), System.currentTimeMillis());
        if (party == null) {
            src.sendFailure(Component.translatableWithFallback("commands.kuronami_quest_tree.party.already",
                    "You are already in a party"));
            return 0;
        }
        dirty(src);
        // materialise the team record now: the player's solo progress is folded into the new party
        ProgressService.progress(player);
        ProgressService.flushTeam(player);
        src.sendSuccess(() -> Component.translatableWithFallback("commands.kuronami_quest_tree.party.created",
                "Party created"), false);
        return 1;
    }

    private static int invite(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSourceStack src = c.getSource();
        if (managedElsewhere(src)) {
            return 0;
        }
        ServerPlayer from = src.getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(c, "player");
        if (!data(src).invite(from.getUUID(), target.getUUID(), System.currentTimeMillis())) {
            src.sendFailure(Component.translatableWithFallback("commands.kuronami_quest_tree.party.invite_failed",
                    "Cannot invite %s", target.getGameProfile().getName()));
            return 0;
        }
        dirty(src);
        src.sendSuccess(() -> Component.translatableWithFallback("commands.kuronami_quest_tree.party.invited",
                "Invited %s", target.getGameProfile().getName()), false);
        target.sendSystemMessage(Component.translatableWithFallback("commands.kuronami_quest_tree.party.invited_you",
                "%s invited you to a party: /quest_tree party accept", from.getGameProfile().getName()));
        return 1;
    }

    private static int accept(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSourceStack src = c.getSource();
        if (managedElsewhere(src)) {
            return 0;
        }
        ServerPlayer player = src.getPlayerOrException();
        UUID joined = data(src).accept(player.getUUID(), System.currentTimeMillis());
        if (joined == null) {
            src.sendFailure(Component.translatableWithFallback("commands.kuronami_quest_tree.party.no_invite",
                    "No valid invite"));
            return 0;
        }
        dirty(src);
        ProgressService.progress(player);
        ProgressService.flushTeam(player);
        src.sendSuccess(() -> Component.translatableWithFallback("commands.kuronami_quest_tree.party.joined",
                "Joined the party"), false);
        return 1;
    }

    private static int leave(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSourceStack src = c.getSource();
        if (managedElsewhere(src)) {
            return 0;
        }
        ServerPlayer player = src.getPlayerOrException();
        PartyData partyData = data(src);
        PartyData.Party old = partyData.partyOf(player.getUUID());
        Set<UUID> oldMembers = old == null ? Set.of() : Set.copyOf(old.members);
        UUID oldId = partyData.leave(player.getUUID(), System.currentTimeMillis());
        if (oldId == null) {
            src.sendFailure(Component.translatableWithFallback("commands.kuronami_quest_tree.party.not_in",
                    "You are not in a party"));
            return 0;
        }
        dirty(src);
        detach(src.getServer(), player.getUUID(), oldId, oldMembers);
        src.sendSuccess(() -> Component.translatableWithFallback("commands.kuronami_quest_tree.party.left",
                "Left the party"), false);
        return 1;
    }

    /**
     * The player is no longer in the team {@code oldId}: they take a copy of its progress now (also when offline),
     * and everyone who shared it is re-sent and gets their attribute modifiers recomputed.
     */
    private static void detach(MinecraftServer server, UUID player, UUID oldId, Set<UUID> oldMembers) {
        ProgressStore.get(server).settleLeave(player);
        ProgressService.teamChanged(server, player, oldId, player, oldMembers);
    }

    private static String nameOf(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        var cache = server.getProfileCache();
        if (cache != null) {
            var profile = cache.get(id);
            if (profile.isPresent()) {
                return profile.get().getName();
            }
        }
        return id.toString();
    }

    /** Names of the caller's party members, offline ones included, for suggestions. */
    private static java.util.List<String> rosterNames(CommandSourceStack src) {
        ServerPlayer self = src.getPlayer();
        PartyData.Party party = self == null ? null : data(src).partyOf(self.getUUID());
        java.util.List<String> out = new java.util.ArrayList<>();
        if (party != null) {
            for (UUID id : party.members) {
                out.add(nameOf(src.getServer(), id));
            }
        }
        return out;
    }

    private static int kick(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSourceStack src = c.getSource();
        if (managedElsewhere(src)) {
            return 0;
        }
        ServerPlayer actor = src.getPlayerOrException();
        MinecraftServer server = src.getServer();
        PartyData.Party old = data(src).partyOf(actor.getUUID());
        UUID target = old == null ? null : PartyData.findMember(old, StringArgumentType.getString(c, "player"),
                id -> nameOf(server, id));
        if (target == null || !data(src).kick(actor.getUUID(), target)) {
            src.sendFailure(Component.translatableWithFallback("commands.kuronami_quest_tree.party.kick_failed",
                    "Only the owner can kick"));
            return 0;
        }
        dirty(src);
        Set<UUID> oldMembers = Set.copyOf(old.members);
        Set<UUID> before = new java.util.HashSet<>(oldMembers);
        before.add(target);
        detach(server, target, old.id, before);
        ServerPlayer online = server.getPlayerList().getPlayer(target);
        if (online != null) {
            online.sendSystemMessage(Component.translatableWithFallback("commands.kuronami_quest_tree.party.kicked_you",
                    "You were removed from the party"));
        }
        src.sendSuccess(() -> Component.translatableWithFallback("commands.kuronami_quest_tree.party.kicked",
                "Removed %s", nameOf(server, target)), false);
        return 1;
    }

    private static int disband(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSourceStack src = c.getSource();
        if (managedElsewhere(src)) {
            return 0;
        }
        ServerPlayer actor = src.getPlayerOrException();
        PartyData.Party old = data(src).partyOf(actor.getUUID());
        if (old == null || old.owner == null || !old.owner.equals(actor.getUUID())) {
            src.sendFailure(Component.translatableWithFallback("commands.kuronami_quest_tree.party.disband_failed",
                    "Only the owner can disband"));
            return 0;
        }
        MinecraftServer server = src.getServer();
        Set<UUID> members = Set.copyOf(old.members);
        UUID oldId = old.id;
        data(src).disband(actor.getUUID());
        dirty(src);
        for (UUID id : members) {
            detach(server, id, oldId, members);
        }
        src.sendSuccess(() -> Component.translatableWithFallback("commands.kuronami_quest_tree.party.disbanded",
                "Party disbanded"), false);
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSourceStack src = c.getSource();
        ServerPlayer player = src.getPlayerOrException();
        MinecraftServer server = src.getServer();
        UUID teamId = TeamResolver.teamId(server, player.getUUID());
        boolean ftb = TeamResolver.managedByFtbTeams();
        src.sendSuccess(() -> Component.translatableWithFallback("commands.kuronami_quest_tree.party.source",
                "Teams: %s", ftb ? "FTB Teams" : "KQT party"), false);
        src.sendSuccess(() -> Component.translatableWithFallback("commands.kuronami_quest_tree.party.team_id",
                "Team id: %s", teamId), false);
        for (UUID id : TeamResolver.members(server, teamId)) {
            String name = nameOf(server, id);
            src.sendSuccess(() -> Component.literal(" - " + name), false);
        }
        if (!ftb) {
            PartyData data = data(src);
            PartyData.Party own = data.partyOf(player.getUUID());
            if (own != null) {
                String owner = nameOf(server, own.owner);
                src.sendSuccess(() -> Component.translatableWithFallback("commands.kuronami_quest_tree.party.owner",
                        "Owner: %s", owner), false);
            }
            long now = System.currentTimeMillis();
            data.invitesOf(teamId, now).forEach((target, invite) -> src.sendSuccess(() -> Component.translatableWithFallback(
                    "commands.kuronami_quest_tree.party.pending", "Invite pending: %s (%ss left)",
                    nameOf(server, target), Math.max(0, (invite.expires - now) / 1000)), false));
        }
        return 1;
    }

}
