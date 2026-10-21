package com.kuronami.questtree.server;

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

    private static PartyData data(CommandSourceStack src) {
        return PartyStore.get(src.getServer()).data();
    }

    private static int answer(CommandSourceStack src, PartyActions.Outcome outcome) {
        if (!outcome.ok()) {
            src.sendFailure(PartyActions.message(outcome));
            return 0;
        }
        src.sendSuccess(() -> PartyActions.message(outcome), false);
        return 1;
    }

    private static int create(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSourceStack src = c.getSource();
        ServerPlayer player = src.getPlayerOrException();
        return answer(src, PartyActions.create(src.getServer(), player.getUUID()));
    }

    private static int invite(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSourceStack src = c.getSource();
        ServerPlayer from = src.getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(c, "player");
        return answer(src, PartyActions.invite(src.getServer(), from.getUUID(), target.getUUID()));
    }

    private static int accept(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSourceStack src = c.getSource();
        ServerPlayer player = src.getPlayerOrException();
        return answer(src, PartyActions.accept(src.getServer(), player.getUUID()));
    }

    private static int leave(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSourceStack src = c.getSource();
        ServerPlayer player = src.getPlayerOrException();
        return answer(src, PartyActions.leave(src.getServer(), player.getUUID()));
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
        ServerPlayer actor = src.getPlayerOrException();
        MinecraftServer server = src.getServer();
        PartyData.Party old = data(src).partyOf(actor.getUUID());
        UUID target = old == null ? null : PartyData.findMember(old, StringArgumentType.getString(c, "player"),
                id -> nameOf(server, id));
        if (target == null) {
            src.sendFailure(PartyActions.message(new PartyActions.Outcome(
                    PartyActions.Kind.KICK_FAILED, StringArgumentType.getString(c, "player"))));
            return 0;
        }
        return answer(src, PartyActions.kick(server, actor.getUUID(), target));
    }

    private static int disband(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSourceStack src = c.getSource();
        ServerPlayer actor = src.getPlayerOrException();
        return answer(src, PartyActions.disband(src.getServer(), actor.getUUID()));
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
