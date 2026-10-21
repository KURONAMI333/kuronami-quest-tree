package com.kuronami.questtree.team;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * KQT's own lightweight party, used when FTB Teams is not installed. Plain data so the invite/accept/expire and
 * kick/disband rules can be tested without the game.
 * <p>
 * A party has one owner and any number of members; membership is a single set per party (a player is in at most one
 * party). An invite names the party and the time it stops being valid (5 minutes). Leaving transfers ownership to
 * the oldest remaining member; disbanding removes the party for everyone.
 */
public final class PartyData {

    public static final long INVITE_EXPIRY_MS = 5 * 60 * 1000L;

    public static final class Party {
        public final UUID id;
        public UUID owner;
        public final LinkedHashSet<UUID> members = new LinkedHashSet<>();

        Party(UUID id, UUID owner) {
            this.id = id;
            this.owner = owner;
            members.add(owner);
        }
    }

    public static final class Invite {
        public final UUID party;
        /** Who sent the invitation, so the party screen can name them. */
        public final UUID from;
        public final long expires;

        Invite(UUID party, UUID from, long expires) {
            this.party = party;
            this.from = from;
            this.expires = expires;
        }
    }

    private final Map<UUID, Party> parties = new LinkedHashMap<>();
    private final Map<UUID, Invite> invites = new LinkedHashMap<>();

    public PartyData() {
    }

    public Party partyOf(UUID player) {
        for (Party p : parties.values()) {
            if (p.members.contains(player)) {
                return p;
            }
        }
        return null;
    }

    /** The id progress is keyed by: the party's id when in one, otherwise the player's own UUID. */
    public UUID teamIdOf(UUID player) {
        Party p = partyOf(player);
        return p == null ? player : p.id;
    }

    public Set<UUID> members(UUID teamId) {
        Party p = parties.get(teamId);
        return p == null ? Set.of(teamId) : Set.copyOf(p.members);
    }

    /** A member of {@code party} named by a name (any case) or a UUID string; null when nobody matches. */
    public static UUID findMember(Party party, String text, java.util.function.Function<UUID, String> names) {
        for (UUID id : party.members) {
            if (id.toString().equalsIgnoreCase(text) || text.equalsIgnoreCase(names.apply(id))) {
                return id;
            }
        }
        return null;
    }

    public Party party(UUID id) {
        return parties.get(id);
    }

    public Set<Party> parties() {
        return Set.copyOf(parties.values());
    }

    public Invite invite(UUID player) {
        return invites.get(player);
    }

    /** Creates a party with a random id; null when the player is already in one. */
    public Party create(UUID owner, long now) {
        return create(owner, UUID.randomUUID(), now);
    }

    public Party create(UUID owner, UUID partyId, long now) {
        if (partyOf(owner) != null || parties.containsKey(partyId)) {
            return null;
        }
        Party p = new Party(partyId, owner);
        parties.put(partyId, p);
        return p;
    }

    /** Invites {@code target} to the inviter's party. The inviter must be a member; the target must be free. */
    public boolean invite(UUID from, UUID target, long now) {
        Party p = partyOf(from);
        if (p == null || from.equals(target) || partyOf(target) != null) {
            return false;
        }
        expire(now);
        invites.put(target, new Invite(p.id, from, now + INVITE_EXPIRY_MS));
        return true;
    }

    /** Drops the invitation to {@code player} without joining. Returns true when there was one. */
    public boolean decline(UUID player) {
        return invites.remove(player) != null;
    }

    /** Accepts the invite that is still valid. Returns the joined party's id, or null. */
    public UUID accept(UUID target, long now) {
        expire(now);
        Invite invite = invites.remove(target);
        if (invite == null || partyOf(target) != null) {
            return null;
        }
        Party p = parties.get(invite.party);
        if (p == null) {
            return null;
        }
        p.members.add(target);
        return p.id;
    }

    /** Leaves the current party. Returns the party id that was left, or null. */
    public UUID leave(UUID player, long now) {
        Party p = partyOf(player);
        if (p == null) {
            return null;
        }
        p.members.remove(player);
        invites.remove(player);
        if (p.members.isEmpty()) {
            parties.remove(p.id);
            invites.entrySet().removeIf(e -> e.getValue().party.equals(p.id));
        } else if (p.owner.equals(player)) {
            p.owner = p.members.iterator().next();
        }
        return p.id;
    }

    /** The owner throws a member out. */
    public boolean kick(UUID actor, UUID target) {
        Party p = partyOf(actor);
        if (p == null || !p.owner.equals(actor) || actor.equals(target) || !p.members.contains(target)) {
            return false;
        }
        p.members.remove(target);
        invites.remove(target);
        return true;
    }

    /** The owner dissolves the party. Returns its id, or null. */
    public UUID disband(UUID actor) {
        Party p = partyOf(actor);
        if (p == null || !p.owner.equals(actor)) {
            return null;
        }
        parties.remove(p.id);
        invites.entrySet().removeIf(e -> e.getValue().party.equals(p.id));
        return p.id;
    }

    /** Still-valid invites that point at one party, by invited player. */
    public Map<UUID, Invite> invitesOf(UUID party, long now) {
        Map<UUID, Invite> out = new LinkedHashMap<>();
        invites.forEach((player, invite) -> {
            if (invite.party.equals(party) && invite.expires > now) {
                out.put(player, invite);
            }
        });
        return out;
    }

    /** Drops invites whose 5 minutes ran out. */
    public void expire(long now) {
        invites.entrySet().removeIf(e -> e.getValue().expires <= now);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        JsonArray ps = new JsonArray();
        for (Party p : parties.values()) {
            JsonObject one = new JsonObject();
            one.addProperty("id", p.id.toString());
            one.addProperty("owner", p.owner.toString());
            JsonArray members = new JsonArray();
            p.members.forEach(m -> members.add(m.toString()));
            one.add("members", members);
            ps.add(one);
        }
        o.add("parties", ps);
        JsonObject is = new JsonObject();
        invites.forEach((player, invite) -> {
            JsonObject one = new JsonObject();
            one.addProperty("party", invite.party.toString());
            one.addProperty("from", invite.from.toString());
            one.addProperty("expires", invite.expires);
            is.add(player.toString(), one);
        });
        o.add("invites", is);
        return o;
    }

    public static PartyData fromJson(JsonObject o) {
        PartyData data = new PartyData();
        if (o.has("parties") && o.get("parties").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("parties")) {
                try {
                    JsonObject one = e.getAsJsonObject();
                    UUID id = UUID.fromString(one.get("id").getAsString());
                    UUID owner = UUID.fromString(one.get("owner").getAsString());
                    Party p = new Party(id, owner);
                    p.members.clear();
                    p.members.add(owner);
                    if (one.has("members") && one.get("members").isJsonArray()) {
                        for (JsonElement m : one.getAsJsonArray("members")) {
                            p.members.add(UUID.fromString(m.getAsString()));
                        }
                    }
                    data.parties.put(id, p);
                } catch (RuntimeException ignored) {
                    // a malformed party is dropped; the rest still load
                }
            }
        }
        if (o.has("invites") && o.get("invites").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("invites").entrySet()) {
                try {
                    JsonObject one = e.getValue().getAsJsonObject();
                    UUID party = UUID.fromString(one.get("party").getAsString());
                    Party p = data.parties.get(party);
                    UUID from = one.has("from")
                            ? UUID.fromString(one.get("from").getAsString())
                            : p == null ? party : p.owner;
                    data.invites.put(UUID.fromString(e.getKey()), new Invite(
                            party, from, com.kuronami.questtree.progress.PackNumbers.savedLong(one.get("expires"), 0L)));
                } catch (RuntimeException ignored) {
                    // ignore a malformed invite
                }
            }
        }
        return data;
    }

    public static PartyData parse(String json) {
        return fromJson(JsonParser.parseString(json).getAsJsonObject());
    }
}
