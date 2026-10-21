package com.kuronami.questtree.server;

import com.kuronami.questtree.network.ActionPayload;
import com.kuronami.questtree.team.PartyData;
import com.kuronami.questtree.team.PartyStore;
import com.kuronami.questtree.team.TeamResolver;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.UUID;

/**
 * The party operations behind both {@code /quest_tree party} and the party screen. Kept out of the command class so
 * the screen does not need a command source; the command only turns the outcome into its success/failure line.
 */
public final class PartyActions {

    public enum Kind {
        CREATED, INVITED, ACCEPTED, DECLINED, LEFT, KICKED, DISBANDED,
        ALREADY_IN, NOT_INVITED, NOT_IN, INVITE_FAILED, KICK_FAILED, DISBAND_FAILED, TARGET_UNKNOWN, FTB_MANAGED
    }

    public record Outcome(Kind kind, String name) {
        public boolean ok() {
            return switch (kind) {
                case CREATED, INVITED, ACCEPTED, DECLINED, LEFT, KICKED, DISBANDED -> true;
                default -> false;
            };
        }
    }

    private PartyActions() {
    }

    public static Outcome create(MinecraftServer server, UUID actor) {
        if (TeamResolver.managedByFtbTeams()) {
            return new Outcome(Kind.FTB_MANAGED, "");
        }
        ServerPlayer player = server.getPlayerList().getPlayer(actor);
        if (player == null) {
            return new Outcome(Kind.NOT_IN, "");
        }
        PartyData data = PartyStore.get(server).data();
        PartyData.Party party = data.create(actor, System.currentTimeMillis());
        if (party == null) {
            return new Outcome(Kind.ALREADY_IN, "");
        }
        dirty(server);
        ProgressService.progress(player);
        ProgressService.flushTeam(player);
        return new Outcome(Kind.CREATED, "");
    }

    public static Outcome invite(MinecraftServer server, UUID actor, UUID target) {
        if (TeamResolver.managedByFtbTeams()) {
            return new Outcome(Kind.FTB_MANAGED, "");
        }
        ServerPlayer from = server.getPlayerList().getPlayer(actor);
        ServerPlayer to = target == null ? null : server.getPlayerList().getPlayer(target);
        if (from == null || to == null) {
            return new Outcome(Kind.TARGET_UNKNOWN, target == null ? "" : TeamSync.nameOf(server, target));
        }
        if (!PartyStore.get(server).data().invite(actor, target, System.currentTimeMillis())) {
            return new Outcome(Kind.INVITE_FAILED, to.getGameProfile().getName());
        }
        dirty(server);
        to.sendSystemMessage(Component.translatableWithFallback("commands.kuronami_quest_tree.party.invited_you",
                "%s invited you to a party", from.getGameProfile().getName()));
        // the target's rail shows the invitation now, without waiting for their next progress update
        TeamSync.send(to);
        return new Outcome(Kind.INVITED, to.getGameProfile().getName());
    }

    public static Outcome accept(MinecraftServer server, UUID actor) {
        if (TeamResolver.managedByFtbTeams()) {
            return new Outcome(Kind.FTB_MANAGED, "");
        }
        ServerPlayer player = server.getPlayerList().getPlayer(actor);
        if (player == null) {
            return new Outcome(Kind.NOT_IN, "");
        }
        UUID joined = PartyStore.get(server).data().accept(actor, System.currentTimeMillis());
        if (joined == null) {
            return new Outcome(Kind.NOT_INVITED, "");
        }
        dirty(server);
        ProgressService.progress(player);
        ProgressService.flushTeam(player);
        return new Outcome(Kind.ACCEPTED, "");
    }

    public static Outcome decline(MinecraftServer server, UUID actor) {
        if (TeamResolver.managedByFtbTeams()) {
            return new Outcome(Kind.FTB_MANAGED, "");
        }
        if (!PartyStore.get(server).data().decline(actor)) {
            return new Outcome(Kind.NOT_INVITED, "");
        }
        dirty(server);
        ServerPlayer player = server.getPlayerList().getPlayer(actor);
        if (player != null) {
            TeamSync.send(player);
        }
        return new Outcome(Kind.DECLINED, "");
    }

    public static Outcome leave(MinecraftServer server, UUID actor) {
        if (TeamResolver.managedByFtbTeams()) {
            return new Outcome(Kind.FTB_MANAGED, "");
        }
        ServerPlayer player = server.getPlayerList().getPlayer(actor);
        if (player == null) {
            return new Outcome(Kind.NOT_IN, "");
        }
        PartyData data = PartyStore.get(server).data();
        PartyData.Party old = data.partyOf(actor);
        Set<UUID> oldMembers = old == null ? Set.of() : Set.copyOf(old.members);
        UUID oldId = data.leave(actor, System.currentTimeMillis());
        if (oldId == null) {
            return new Outcome(Kind.NOT_IN, "");
        }
        dirty(server);
        detach(server, actor, oldId, oldMembers);
        return new Outcome(Kind.LEFT, "");
    }

    public static Outcome kick(MinecraftServer server, UUID actor, UUID target) {
        if (TeamResolver.managedByFtbTeams()) {
            return new Outcome(Kind.FTB_MANAGED, "");
        }
        ServerPlayer player = server.getPlayerList().getPlayer(actor);
        if (player == null || target == null) {
            return new Outcome(Kind.KICK_FAILED, target == null ? "" : TeamSync.nameOf(server, target));
        }
        PartyData data = PartyStore.get(server).data();
        PartyData.Party old = data.partyOf(actor);
        if (old == null || !data.kick(actor, target)) {
            return new Outcome(Kind.KICK_FAILED, TeamSync.nameOf(server, target));
        }
        dirty(server);
        Set<UUID> before = new java.util.HashSet<>(old.members);
        before.add(target);
        detach(server, target, old.id, before);
        ServerPlayer online = server.getPlayerList().getPlayer(target);
        if (online != null) {
            online.sendSystemMessage(Component.translatableWithFallback("commands.kuronami_quest_tree.party.kicked_you",
                    "You were removed from the party"));
        }
        return new Outcome(Kind.KICKED, TeamSync.nameOf(server, target));
    }

    public static Outcome disband(MinecraftServer server, UUID actor) {
        if (TeamResolver.managedByFtbTeams()) {
            return new Outcome(Kind.FTB_MANAGED, "");
        }
        PartyData data = PartyStore.get(server).data();
        PartyData.Party old = data.partyOf(actor);
        if (old == null || old.owner == null || !old.owner.equals(actor)) {
            return new Outcome(Kind.DISBAND_FAILED, "");
        }
        Set<UUID> members = Set.copyOf(old.members);
        UUID oldId = old.id;
        data.disband(actor);
        dirty(server);
        for (UUID id : members) {
            detach(server, id, oldId, members);
        }
        return new Outcome(Kind.DISBANDED, "");
    }

    /** The screen's action packet. The server re-checks everything, exactly as the command does. */
    public static void handle(ServerPlayer player, ActionPayload action) {
        UUID target = null;
        if (action.action().equals("party_invite") || action.action().equals("party_kick")) {
            try {
                target = UUID.fromString(action.node());
            } catch (RuntimeException ex) {
                player.sendSystemMessage(message(new Outcome(Kind.TARGET_UNKNOWN, "")));
                return;
            }
        }
        Outcome outcome = switch (action.action()) {
            case "party_create" -> create(player.server, player.getUUID());
            case "party_invite" -> invite(player.server, player.getUUID(), target);
            case "party_accept" -> accept(player.server, player.getUUID());
            case "party_decline" -> decline(player.server, player.getUUID());
            case "party_leave" -> leave(player.server, player.getUUID());
            case "party_kick" -> kick(player.server, player.getUUID(), target);
            case "party_disband" -> disband(player.server, player.getUUID());
            default -> null;
        };
        if (outcome == null) {
            return;
        }
        player.sendSystemMessage(message(outcome));
        if (outcome.ok()) {
            TeamSync.broadcast(player.server);
        } else {
            // the action failed because the client's party was stale; let it catch up
            TeamSync.send(player);
        }
    }

    public static Component message(Outcome outcome) {
        return switch (outcome.kind()) {
            case CREATED -> tr("commands.kuronami_quest_tree.party.created", "Party created");
            case INVITED -> tr("commands.kuronami_quest_tree.party.invited", "Invited %s", outcome.name());
            case ACCEPTED -> tr("commands.kuronami_quest_tree.party.joined", "Joined the party");
            case DECLINED -> tr("commands.kuronami_quest_tree.party.declined", "Invitation declined");
            case LEFT -> tr("commands.kuronami_quest_tree.party.left", "Left the party");
            case KICKED -> tr("commands.kuronami_quest_tree.party.kicked", "Removed %s", outcome.name());
            case DISBANDED -> tr("commands.kuronami_quest_tree.party.disbanded", "Party disbanded");
            case ALREADY_IN -> tr("commands.kuronami_quest_tree.party.already", "You are already in a party");
            case NOT_INVITED -> tr("commands.kuronami_quest_tree.party.no_invite", "No valid invite");
            case NOT_IN -> tr("commands.kuronami_quest_tree.party.not_in", "You are not in a party");
            case INVITE_FAILED -> tr("commands.kuronami_quest_tree.party.invite_failed", "Cannot invite %s", outcome.name());
            case KICK_FAILED -> tr("commands.kuronami_quest_tree.party.kick_failed", "Only the owner can kick");
            case DISBAND_FAILED -> tr("commands.kuronami_quest_tree.party.disband_failed", "Only the owner can disband");
            case TARGET_UNKNOWN -> tr("commands.kuronami_quest_tree.party.target_unknown", "That player is not online");
            case FTB_MANAGED -> tr("commands.kuronami_quest_tree.party.ftb",
                    "Parties are managed by FTB Teams. Use /ftbteams party instead.");
        };
    }

    private static Component tr(String key, String fallback, Object... args) {
        return Component.translatableWithFallback(key, fallback, args);
    }

    private static void dirty(MinecraftServer server) {
        PartyStore.get(server).setDirty();
    }

    /** The player is no longer in the team: they take a copy of its progress now, also when offline. */
    private static void detach(MinecraftServer server, UUID player, UUID oldId, Set<UUID> oldMembers) {
        ProgressStore.get(server).settleLeave(player);
        ProgressService.teamChanged(server, player, oldId, player, oldMembers);
    }
}
