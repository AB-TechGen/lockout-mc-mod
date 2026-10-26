package dev.opendraft.goal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One goal as it can appear on a board: a Draftout goal, plus its variant when it has
 * several (for example {@code obtain_64_colored_wool:red}). The key is what gets sent
 * over the network, so every player's copy of the mod resolves the same goal.
 */
public final class Goal {
    public final String key;
    public final String id;
    /** Draftout's variant segment, or "null" for goals without variants (matches the icon path). */
    public final String variant;
    public final String name;
    public final String type;
    public final String group;
    /** The goal's parameters with {v}, {v0} and {v1} already filled in. */
    public final JsonObject p;

    Goal(String id, String variant, String name, String type, String group, JsonObject p) {
        this.key = variant.equals("null") ? id : id + ":" + variant;
        this.id = id;
        this.variant = variant;
        this.name = name;
        this.type = type;
        this.group = group == null ? id : group;
        this.p = p;
    }

    public String str(String field, String fallback) {
        JsonElement e = p.get(field);
        return e == null || e.isJsonNull() ? fallback : e.getAsString();
    }

    public int integer(String field, int fallback) {
        JsonElement e = p.get(field);
        return e == null || e.isJsonNull() ? fallback : e.getAsInt();
    }

    public List<String> list(String field) {
        JsonElement e = p.get(field);
        List<String> out = new ArrayList<>();
        if (e == null || e.isJsonNull()) return out;
        if (e.isJsonArray()) {
            for (JsonElement x : (JsonArray) e) out.add(x.getAsString());
        } else {
            out.add(e.getAsString());
        }
        return out;
    }

    public JsonObject obj(String field) {
        JsonElement e = p.get(field);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    @Override
    public String toString() {
        return key;
    }
}
