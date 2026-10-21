package com.kuronami.questtree.team;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * What the client needs to know about the player's team and party invitations, sent by the server and kept free of
 * Minecraft classes so the shape and the invite rules can be tested without the game.
 * <p>
 * A solo player has one member (themselves); {@link #inTeam()} is false then, and the party row stays hidden. FTB
 * Teams manages its own invitations, so {@link #invites} is empty when {@link #ftbManaged()} is true.
 */
public final class TeamView {

    public static final TeamView EMPTY = new TeamView("", "", "", "", false, false, List.of(), List.of());

    public record Member(String id, String name, boolean online, boolean self, boolean owner) {
    }

    public record Invite(String partyId, String partyName, String fromName, long expires) {
    }

    private final String id;
    private final String name;
    private final String ownerId;
    private final String ownerName;
    private final boolean ftbManaged;
    /** True for a real party/team, including a party that currently has only its owner. */
    private final boolean inTeam;
    private final List<Member> members;
    private final List<Invite> invites;

    public TeamView(String id, String name, String ownerId, String ownerName, boolean ftbManaged, boolean inTeam,
                    List<Member> members, List<Invite> invites) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name;
        this.ownerId = ownerId == null ? "" : ownerId;
        this.ownerName = ownerName == null ? "" : ownerName;
        this.ftbManaged = ftbManaged;
        this.inTeam = inTeam;
        this.members = List.copyOf(members);
        this.invites = List.copyOf(invites);
    }

    public String id() {
        return id;
    }

    /** The team's own name; empty for a built-in party, which the client names after its owner. */
    public String name() {
        return name;
    }

    public String ownerId() {
        return ownerId;
    }

    public String ownerName() {
        return ownerName;
    }

    public boolean ftbManaged() {
        return ftbManaged;
    }

    public List<Member> members() {
        return members;
    }

    public List<Invite> invites() {
        return invites;
    }

    public boolean inTeam() {
        return inTeam;
    }

    public boolean hasInvites() {
        return !invites.isEmpty();
    }

    /** True when at least one invite has not expired at {@code now}; the rail's gold dot uses the synced expiry. */
    public boolean hasActiveInvites(long now) {
        return !activeInvites(now).isEmpty();
    }

    /** The party row and the details labels are on when the player shares a team or has an invitation to answer. */
    public boolean active() {
        return inTeam() || hasInvites();
    }

    public Member self() {
        for (Member m : members) {
            if (m.self()) {
                return m;
            }
        }
        return null;
    }

    public Member member(String playerId) {
        for (Member m : members) {
            if (m.id().equals(playerId)) {
                return m;
            }
        }
        return null;
    }

    public boolean isOwner() {
        Member self = self();
        return self != null && self.owner();
    }

    /** Solo players own their own pool; in a team, only the recorded owner may respec or exchange. */
    public boolean ownsSharedActions() {
        return !inTeam || isOwner();
    }

    /** The name to show for a member, or the id when the server could not resolve one. */
    public String nameOf(String playerId) {
        Member m = member(playerId);
        return m == null || m.name().isBlank() ? playerId : m.name();
    }

    public List<Invite> activeInvites(long now) {
        List<Invite> out = new ArrayList<>();
        for (Invite invite : invites) {
            if (invite.expires() > now) {
                out.add(invite);
            }
        }
        return out;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        o.addProperty("owner_id", ownerId);
        o.addProperty("owner_name", ownerName);
        o.addProperty("ftb_managed", ftbManaged);
        o.addProperty("in_team", inTeam);
        JsonArray ms = new JsonArray();
        for (Member m : members) {
            JsonObject one = new JsonObject();
            one.addProperty("id", m.id());
            one.addProperty("name", m.name());
            one.addProperty("online", m.online());
            one.addProperty("self", m.self());
            one.addProperty("owner", m.owner());
            ms.add(one);
        }
        o.add("members", ms);
        JsonArray is = new JsonArray();
        for (Invite invite : invites) {
            JsonObject one = new JsonObject();
            one.addProperty("party_id", invite.partyId());
            one.addProperty("party_name", invite.partyName());
            one.addProperty("from_name", invite.fromName());
            one.addProperty("expires", invite.expires());
            is.add(one);
        }
        o.add("invites", is);
        return o;
    }

    public static TeamView fromJson(JsonObject o) {
        List<Member> members = new ArrayList<>();
        if (o.has("members") && o.get("members").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("members")) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject one = e.getAsJsonObject();
                try {
                    members.add(new Member(str(one, "id"), str(one, "name"), bool(one, "online"),
                            bool(one, "self"), bool(one, "owner")));
                } catch (RuntimeException ignored) {
                    // one malformed member must not hide the rest of the team
                }
            }
        }
        List<Invite> invites = new ArrayList<>();
        if (o.has("invites") && o.get("invites").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("invites")) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject one = e.getAsJsonObject();
                try {
                    invites.add(new Invite(str(one, "party_id"), str(one, "party_name"), str(one, "from_name"),
                            one.has("expires") ? one.get("expires").getAsLong() : 0L));
                } catch (RuntimeException ignored) {
                    // a malformed invite is dropped
                }
            }
        }
        boolean inTeam = o.has("in_team") ? bool(o, "in_team") : members.size() > 1;
        return new TeamView(str(o, "id"), str(o, "name"), str(o, "owner_id"), str(o, "owner_name"),
                bool(o, "ftb_managed"), inTeam, members, invites);
    }

    public static TeamView parse(String json) {
        try {
            return fromJson(JsonParser.parseString(json).getAsJsonObject());
        } catch (RuntimeException ex) {
            return EMPTY;
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    private static boolean bool(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean();
    }
}
