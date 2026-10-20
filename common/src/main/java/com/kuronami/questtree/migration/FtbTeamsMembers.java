package com.kuronami.questtree.migration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kuronami.questtree.importer.Snbt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Who belongs to which FTB Teams team, read from {@code <world>/ftbteams/{player,party,server}/*.snbt} (2026-10-20).
 * A player team is the player alone (its id is the player's UUID); a party lists its members under {@code ranks}
 * (member, officer, owner count; ally, invited and enemy do not) and names an {@code owner}. The members of a
 * {@code server} team come from the same {@code ranks} map; FTB Teams' own server team class was not seen adding
 * members of its own, so those are reported by the caller as unconfirmed.
 */
public final class FtbTeamsMembers {

    private static final Set<String> MEMBER_RANKS = Set.of("member", "officer", "owner");

    private final Map<UUID, Set<UUID>> members = new HashMap<>();
    private int serverTeams;

    /** Adds one team file's content. */
    public void add(JsonObject team) {
        if (!team.has("id")) {
            return;
        }
        UUID id = FtbqIds.uuid(team.get("id").getAsString());
        String type = team.has("type") ? team.get("type").getAsString() : "";
        Set<UUID> set = new LinkedHashSet<>();
        if (type.equals("player")) {
            set.add(id);
        } else {
            if (type.equals("server")) {
                serverTeams++;
            }
            if (team.has("owner") && team.get("owner").isJsonPrimitive()) {
                set.add(FtbqIds.uuid(team.get("owner").getAsString()));
            }
            if (team.has("ranks") && team.get("ranks").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : team.getAsJsonObject("ranks").entrySet()) {
                    if (e.getValue().isJsonPrimitive() && MEMBER_RANKS.contains(e.getValue().getAsString())) {
                        set.add(FtbqIds.uuid(e.getKey()));
                    }
                }
            }
        }
        members.put(id, set);
    }

    /** The members of a team, or null when FTB Teams has no file for that id. */
    public Set<UUID> members(UUID team) {
        return members.get(team);
    }

    public int serverTeams() {
        return serverTeams;
    }

    public int teams() {
        return members.size();
    }

    /** Reads every team file below an {@code ftbteams} folder; unreadable files are returned as a count. */
    public static FtbTeamsMembers read(Path ftbteams, int[] unreadable) throws IOException {
        FtbTeamsMembers out = new FtbTeamsMembers();
        for (String type : new String[]{"player", "party", "server"}) {
            Path dir = ftbteams.resolve(type);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> list = Files.list(dir)) {
                for (Path f : list.filter(p -> p.toString().endsWith(".snbt")).sorted().toList()) {
                    try {
                        JsonObject o = Snbt.parse(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
                        if (!o.has("type")) {
                            o.addProperty("type", type);
                        }
                        out.add(o);
                    } catch (RuntimeException ex) {
                        unreadable[0]++;
                    }
                }
            }
        }
        return out;
    }
}
