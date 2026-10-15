package com.kuronami.packprogress.progress;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;

/** PlayerProgress to and from JSON (used for saving inside NBT and for syncing to the client). */
public final class ProgressJson {

    private ProgressJson() {
    }

    public static JsonObject write(PlayerProgress p) {
        JsonObject o = new JsonObject();
        o.add("completed", strings(p.completed));
        o.add("claimed", strings(p.claimed));
        JsonObject tasks = new JsonObject();
        p.tasks.forEach(tasks::addProperty);
        o.add("tasks", tasks);
        o.add("taken", ints(p.taken));
        JsonObject xp = new JsonObject();
        p.xp.forEach(xp::addProperty);
        o.add("xp", xp);
        o.add("level", ints(p.level));
        o.add("spent", ints(p.spent));
        o.add("bonus", ints(p.bonus));
        o.add("recent", strings(p.recent));
        o.add("repeats", ints(p.repeats));
        JsonObject cd = new JsonObject();
        p.cooldown.forEach(cd::addProperty);
        o.add("cooldown", cd);
        return o;
    }

    public static PlayerProgress read(JsonObject o) {
        PlayerProgress p = new PlayerProgress();
        for (JsonElement e : arr(o, "completed")) {
            p.completed.add(e.getAsString());
        }
        for (JsonElement e : arr(o, "claimed")) {
            p.claimed.add(e.getAsString());
        }
        for (Map.Entry<String, JsonElement> e : obj(o, "tasks").entrySet()) {
            p.tasks.put(e.getKey(), e.getValue().getAsLong());
        }
        readInts(obj(o, "taken"), p.taken);
        for (Map.Entry<String, JsonElement> e : obj(o, "xp").entrySet()) {
            p.xp.put(e.getKey(), e.getValue().getAsDouble());
        }
        readInts(obj(o, "level"), p.level);
        readInts(obj(o, "spent"), p.spent);
        readInts(obj(o, "bonus"), p.bonus);
        readInts(obj(o, "repeats"), p.repeats);
        for (Map.Entry<String, JsonElement> e : obj(o, "cooldown").entrySet()) {
            p.cooldown.put(e.getKey(), e.getValue().getAsLong());
        }
        for (JsonElement e : arr(o, "recent")) {
            p.recent.addLast(e.getAsString());
        }
        return p;
    }

    private static JsonArray strings(Iterable<String> it) {
        JsonArray a = new JsonArray();
        it.forEach(a::add);
        return a;
    }

    private static JsonObject ints(Map<String, Integer> m) {
        JsonObject o = new JsonObject();
        m.forEach(o::addProperty);
        return o;
    }

    private static void readInts(JsonObject o, Map<String, Integer> into) {
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            into.put(e.getKey(), e.getValue().getAsInt());
        }
    }

    private static JsonObject obj(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : new JsonObject();
    }

    private static JsonArray arr(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }
}
