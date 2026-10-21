package com.kuronami.questtree.team;

import com.kuronami.questtree.platform.Services;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Chooses where teams come from. FTB Teams wins when it is installed (one party model per pack); otherwise KQT's
 * own party store is used. A solo player is always a team whose id is their own UUID, so callers need no special
 * case. The FTB Teams classes are only touched from {@link FtbTeamsBridge}, which is loaded only when the mod is.
 */
public final class TeamResolver {

    public enum Source { FTB_TEAMS, BUILTIN }

    private TeamResolver() {
    }

    /** Pure selection rule, kept separate so it can be tested without a running game. */
    public static Source source(boolean ftbTeamsInstalled) {
        return ftbTeamsInstalled ? Source.FTB_TEAMS : Source.BUILTIN;
    }

    public static Source source() {
        return source(Services.PLATFORM.isModLoaded("ftbteams"));
    }

    public static boolean managedByFtbTeams() {
        return source() == Source.FTB_TEAMS;
    }

    public static UUID teamId(MinecraftServer server, UUID player) {
        if (managedByFtbTeams()) {
            return FtbTeamsBridge.teamId(server, player);
        }
        return PartyStore.get(server).data().teamIdOf(player);
    }

    public static Set<UUID> members(MinecraftServer server, UUID teamId) {
        if (managedByFtbTeams()) {
            return FtbTeamsBridge.members(server, teamId);
        }
        return PartyStore.get(server).data().members(teamId);
    }

    /** The FTB Teams owner; null for the built-in party, where the party record has it. */
    public static UUID teamOwner(MinecraftServer server, UUID teamId) {
        return managedByFtbTeams() ? FtbTeamsBridge.owner(server, teamId) : null;
    }

    /** The FTB team's friendly name; empty for the built-in party, which the client names after its owner. */
    public static String teamName(MinecraftServer server, UUID teamId) {
        return managedByFtbTeams() ? FtbTeamsBridge.name(server, teamId) : "";
    }

    /** Online members of the player's current team (the player themselves when the list cannot be read). */
    public static Set<ServerPlayer> onlineMembers(MinecraftServer server, UUID player) {
        UUID teamId = teamId(server, player);
        Set<ServerPlayer> out = new LinkedHashSet<>();
        for (UUID id : members(server, teamId)) {
            ServerPlayer online = server.getPlayerList().getPlayer(id);
            if (online != null) {
                out.add(online);
            }
        }
        ServerPlayer self = server.getPlayerList().getPlayer(player);
        if (self != null) {
            out.add(self);
        }
        return out;
    }
}
