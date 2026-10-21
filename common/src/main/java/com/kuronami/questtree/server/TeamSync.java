package com.kuronami.questtree.server;

import com.kuronami.questtree.network.TeamPayload;
import com.kuronami.questtree.platform.Services;
import com.kuronami.questtree.team.PartyData;
import com.kuronami.questtree.team.PartyStore;
import com.kuronami.questtree.team.TeamResolver;
import com.kuronami.questtree.team.TeamView;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the client's {@link TeamView} from the live party or FTB Teams team and sends it with the progress packet.
 * Only the last JSON is kept per player, so a tick that changes experience does not resend an unchanged team.
 */
public final class TeamSync {

    private static final Map<UUID, String> LAST = new HashMap<>();

    private TeamSync() {
    }

    public static TeamView view(MinecraftServer server, UUID player) {
        return view(server, player, null);
    }

    /**
     * {@code leaving} is shown as offline even while the server still lists them: the logout event fires before the
     * player is removed, so without this the view of a departing member would look unchanged and never be resent.
     */
    public static TeamView view(MinecraftServer server, UUID player, UUID leaving) {
        UUID teamId = TeamResolver.teamId(server, player);
        Set<UUID> ids = TeamResolver.members(server, teamId);
        if (ids.isEmpty()) {
            ids = Set.of(player);
        }
        boolean ftb = TeamResolver.managedByFtbTeams();
        PartyData.Party party = ftb ? null : PartyStore.get(server).data().party(teamId);
        UUID ownerId = ftb ? TeamResolver.teamOwner(server, teamId) : party == null ? player : party.owner;
        if (ownerId == null) {
            ownerId = player;
        }
        String ownerName = nameOf(server, ownerId);
        String name = ftb ? TeamResolver.teamName(server, teamId) : "";

        List<TeamView.Member> members = new ArrayList<>();
        for (UUID id : ids) {
            members.add(new TeamView.Member(id.toString(), nameOf(server, id),
                    server.getPlayerList().getPlayer(id) != null && !id.equals(leaving),
                    id.equals(player), id.equals(ownerId)));
        }
        members.sort(Comparator.comparing((TeamView.Member m) -> !m.owner())
                .thenComparing(m -> !m.self())
                .thenComparing(TeamView.Member::name, String.CASE_INSENSITIVE_ORDER));

        List<TeamView.Invite> invites = new ArrayList<>();
        if (!ftb) {
            PartyData data = PartyStore.get(server).data();
            PartyData.Invite invite = data.invite(player);
            if (invite != null && invite.expires > System.currentTimeMillis()) {
                PartyData.Party invited = data.party(invite.party);
                String partyOwner = invited == null ? "" : nameOf(server, invited.owner);
                invites.add(new TeamView.Invite(invite.party.toString(), partyOwner,
                        nameOf(server, invite.from), invite.expires));
            }
        }
        // a party with only its owner is still a party (the owner can invite from the screen); a solo player is not
        boolean inTeam = ftb ? !teamId.equals(player) : party != null;
        return new TeamView(teamId.toString(), name, ownerId.toString(), ownerName, ftb, inTeam, members, invites);
    }

    /** Sends the view when it differs from the last one this player received. */
    public static void send(ServerPlayer player) {
        send(player, null);
    }

    private static void send(ServerPlayer player, UUID leaving) {
        TeamView view = view(player.server, player.getUUID(), leaving);
        String json = view.toJson().toString();
        if (json.equals(LAST.get(player.getUUID()))) {
            return;
        }
        LAST.put(player.getUUID(), json);
        Services.PLATFORM.sendToPlayer(player, TeamPayload.of(json));
    }

    /** Sends to every online player; membership changes touch both the old and the new team. */
    public static void broadcast(MinecraftServer server) {
        broadcast(server, null);
    }

    /** Broadcasts while treating {@code leaving} as offline; they are skipped too, since they are on their way out. */
    public static void broadcast(MinecraftServer server, UUID leaving) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getUUID().equals(leaving)) {
                continue;
            }
            send(player, leaving);
        }
    }

    public static void forget(UUID player) {
        LAST.remove(player);
    }

    public static void clear() {
        LAST.clear();
    }

    static String nameOf(MinecraftServer server, UUID id) {
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
}
