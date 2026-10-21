package com.kuronami.questtree.team;

import com.kuronami.questtree.Constants;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import net.minecraft.server.MinecraftServer;

import java.util.Set;
import java.util.UUID;

/**
 * The only class that mentions FTB Teams. It must be called only after {@link TeamResolver} has checked that the mod
 * is installed, so a pack without FTB Teams never loads these classes.
 */
final class FtbTeamsBridge {

    private FtbTeamsBridge() {
    }

    static UUID teamId(MinecraftServer server, UUID player) {
        try {
            FTBTeamsAPI.API api = FTBTeamsAPI.api();
            if (api == null || !api.isManagerLoaded()) {
                return player;
            }
            return api.getManager().getTeamForPlayerID(player).map(Team::getTeamId).orElse(player);
        } catch (RuntimeException ex) {
            Constants.LOG.warn("Could not read the FTB Teams team of {}; using the solo record", player);
            return player;
        }
    }

    static Set<UUID> members(MinecraftServer server, UUID teamId) {
        try {
            FTBTeamsAPI.API api = FTBTeamsAPI.api();
            if (api == null || !api.isManagerLoaded()) {
                return Set.of(teamId);
            }
            return api.getManager().getTeamByID(teamId).map(Team::getMembers).orElse(Set.of(teamId));
        } catch (RuntimeException ex) {
            Constants.LOG.warn("Could not read the members of team {}; assuming the team id is the player", teamId);
            return Set.of(teamId);
        }
    }

    /**
     * Whether FTB Teams says this player has a team. Unreadable counts as "has a team", so a failure cannot make a
     * team member look solo and pass the owner-only check (2026-10-22 review).
     */
    static boolean hasTeam(MinecraftServer server, UUID player) {
        try {
            FTBTeamsAPI.API api = FTBTeamsAPI.api();
            if (api == null || !api.isManagerLoaded()) {
                return true;
            }
            return api.getManager().getTeamForPlayerID(player).isPresent();
        } catch (RuntimeException ex) {
            Constants.LOG.warn("Could not read the FTB Teams team of {}; refusing owner-only actions", player);
            return true;
        }
    }

    static UUID owner(MinecraftServer server, UUID teamId) {
        try {
            FTBTeamsAPI.API api = FTBTeamsAPI.api();
            if (api == null || !api.isManagerLoaded()) {
                return null;
            }
            return api.getManager().getTeamByID(teamId).map(Team::getOwner).orElse(null);
        } catch (RuntimeException ex) {
            Constants.LOG.warn("Could not read the owner of team {}", teamId);
            return null;
        }
    }

    /** The team's friendly name; a plain string so no FTB or Minecraft type reaches the client. */
    static String name(MinecraftServer server, UUID teamId) {
        try {
            FTBTeamsAPI.API api = FTBTeamsAPI.api();
            if (api == null || !api.isManagerLoaded()) {
                return "";
            }
            return api.getManager().getTeamByID(teamId).map(Team::getShortName).orElse("");
        } catch (RuntimeException ex) {
            Constants.LOG.warn("Could not read the name of team {}", teamId);
            return "";
        }
    }
}
