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
        Cues.partyInvite(to, from.getGameProfile().getName());
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
            Cues.partyKick(online);
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
                Outcome unknown = new Outcome(Kind.TARGET_UNKNOWN, "");
                Cues.partyResult(player, key(unknown.kind()), unknown.name());
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
        Cues.partyResult(player, key(outcome.kind()), outcome.name());
        if (outcome.ok()) {
            TeamSync.broadcast(player.server);
        } else {
            // the action failed because the client's party was stale; let it catch up
            TeamSync.send(player);
        }
    }

    public static Component message(Outcome outcome) {
        return Component.translatableWithFallback(key(outcome.kind()), fallback(outcome.kind()), outcome.name());
    }

    /** The language key for an outcome, also used by the party screen's status line. */
    static String key(Kind kind) {
        return switch (kind) {
            case CREATED -> "commands.kuronami_quest_tree.party.created";
            case INVITED -> "commands.kuronami_quest_tree.party.invited";
            case ACCEPTED -> "commands.kuronami_quest_tree.party.joined";
            case DECLINED -> "commands.kuronami_quest_tree.party.declined";
            case LEFT -> "commands.kuronami_quest_tree.party.left";
            case KICKED -> "commands.kuronami_quest_tree.party.kicked";
            case DISBANDED -> "commands.kuronami_quest_tree.party.disbanded";
            case ALREADY_IN -> "commands.kuronami_quest_tree.party.already";
            case NOT_INVITED -> "commands.kuronami_quest_tree.party.no_invite";
            case NOT_IN -> "commands.kuronami_quest_tree.party.not_in";
            case INVITE_FAILED -> "commands.kuronami_quest_tree.party.invite_failed";
            case KICK_FAILED -> "commands.kuronami_quest_tree.party.kick_failed";
            case DISBAND_FAILED -> "commands.kuronami_quest_tree.party.disband_failed";
            case TARGET_UNKNOWN -> "commands.kuronami_quest_tree.party.target_unknown";
            case FTB_MANAGED -> "commands.kuronami_quest_tree.party.ftb";
        };
    }

    private static String fallback(Kind kind) {
        return switch (kind) {
            case CREATED -> "Party created";
            case INVITED -> "Invited %s";
            case ACCEPTED -> "Joined the party";
            case DECLINED -> "Invitation declined";
            case LEFT -> "Left the party";
            case KICKED -> "Removed %s";
            case DISBANDED -> "Party disbanded";
            case ALREADY_IN -> "You are already in a party";
            case NOT_INVITED -> "No valid invite";
            case NOT_IN -> "You are not in a party";
            case INVITE_FAILED -> "Cannot invite %s";
            case KICK_FAILED -> "Only the owner can kick";
            case DISBAND_FAILED -> "Only the owner can disband";
            case TARGET_UNKNOWN -> "That player is not online";
            case FTB_MANAGED -> "Parties are managed by FTB Teams. Use /ftbteams party instead.";
        };
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
