package com.kuronami.questtree.giver;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Entity givers of one world: an entity UUID to the quest or chapter it opens. Kept as a plain map with a JSON form so
 * a test can save and load it without a game; {@code GiverStore} puts that JSON into the world save through the
 * mod's own safe save path.
 */
public final class GiverLinks {

    private final Map<UUID, GiverTarget> byEntity = new LinkedHashMap<>();

    public GiverTarget get(UUID entity) {
        return entity == null ? null : byEntity.get(entity);
    }

    public GiverTarget get(String entity) {
        UUID id = parseId(entity);
        return id == null ? null : byEntity.get(id);
    }

    public void set(UUID entity, GiverTarget target) {
        if (entity == null || target == null) {
            return;
        }
        byEntity.put(entity, target);
    }

    /** Returns whether a link was there. */
    public boolean clear(UUID entity) {
        return entity != null && byEntity.remove(entity) != null;
    }

    public int size() {
        return byEntity.size();
    }

    public Map<UUID, GiverTarget> all() {
        return Collections.unmodifiableMap(byEntity);
    }

    /** The saved form: {@code {"<uuid>": "node|<id>"}}; invalid entries are dropped on read. */
    public JsonObject toJson() {
        JsonObject out = new JsonObject();
        byEntity.forEach((id, target) -> out.addProperty(id.toString(), target.encode()));
        return out;
    }

    public static GiverLinks fromJson(JsonObject json) {
        GiverLinks links = new GiverLinks();
        if (json == null) {
            return links;
        }
        for (Map.Entry<String, JsonElement> e : json.entrySet()) {
            UUID id = parseId(e.getKey());
            GiverTarget target = e.getValue().isJsonPrimitive() ? GiverTarget.decode(e.getValue().getAsString()) : null;
            if (id != null && target != null) {
                links.set(id, target);
            }
        }
        return links;
    }

    private static UUID parseId(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
