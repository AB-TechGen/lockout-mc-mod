package dev.opendraft.goal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.opendraft.OpenDraftClient;
import net.minecraft.server.MinecraftServer;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Loads the goal list from {@code /opendraft/goals.json} inside the mod jar. */
public final class Goals {
    private static Map<String, Goal> byKey;
    /** Goal id -> its variants, in catalogue order. */
    private static Map<String, List<Goal>> byId;

    private Goals() {
    }

    public static synchronized void load() {
        if (byKey != null) return;
        Map<String, Goal> keys = new LinkedHashMap<>();
        Map<String, List<Goal>> ids = new LinkedHashMap<>();
        try (InputStream in = Goals.class.getResourceAsStream("/opendraft/goals.json")) {
            if (in == null) throw new IllegalStateException("goals.json is missing from the mod jar");
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement el : root.getAsJsonArray("goals")) {
                JsonObject g = el.getAsJsonObject();
                String id = g.get("id").getAsString();
                String name = g.get("name").getAsString();
                String type = g.get("type").getAsString();
                String group = g.has("group") ? g.get("group").getAsString() : null;
                List<String> variants = new ArrayList<>();
                List<String> variantNames = new ArrayList<>();
                if (g.has("variants")) {
                    for (JsonElement v : g.getAsJsonArray("variants")) variants.add(v.getAsString());
                    for (JsonElement v : g.getAsJsonArray("variant_names")) variantNames.add(v.getAsString());
                } else {
                    variants.add("null");
                    variantNames.add(name);
                }
                List<Goal> list = new ArrayList<>();
                for (int i = 0; i < variants.size(); i++) {
                    String v = variants.get(i);
                    JsonObject params = (JsonObject) substitute(g, v);
                    Goal goal = new Goal(id, v, variantNames.get(i), type, group, params);
                    keys.put(goal.key, goal);
                    list.add(goal);
                }
                ids.put(id, list);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not load the goals", e);
        }
        byKey = keys;
        byId = ids;
        OpenDraftClient.LOG.info("Loaded {} goals ({} with variants)", ids.size(), keys.size());
    }

    private static JsonElement substitute(JsonElement e, String variant) {
        if (variant.equals("null")) return e.deepCopy();
        if (e.isJsonObject()) {
            JsonObject out = new JsonObject();
            for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
                if (en.getKey().equals("variants") || en.getKey().equals("variant_names")) continue;
                out.add(en.getKey(), substitute(en.getValue(), variant));
            }
            return out;
        }
        if (e.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement x : e.getAsJsonArray()) out.add(substitute(x, variant));
            return out;
        }
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            String s = e.getAsString();
            String[] parts = variant.split("__", 2);
            s = s.replace("{v0}", parts[0]).replace("{v1}", parts.length > 1 ? parts[1] : "").replace("{v}", variant);
            return new JsonPrimitive(s);
        }
        return e.deepCopy();
    }

    public static Goal get(String key) {
        load();
        return byKey.get(key);
    }

    public static Map<String, List<Goal>> all() {
        load();
        return Collections.unmodifiableMap(byId);
    }

    /**
     * Picks a random board: at most one goal per group (so "Kill 7 unique" and "Kill 15 unique"
     * never share a board), one random variant per goal, and only goals this game can detect.
     */
    public static List<String> pickBoard(int size, MinecraftServer server, Random random) {
        load();
        List<String> ids = new ArrayList<>(byId.keySet());
        Collections.shuffle(ids, random);
        Set<String> groups = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            if (out.size() >= size) break;
            List<Goal> variants = new ArrayList<>(byId.get(id));
            Collections.shuffle(variants, random);
            for (Goal g : variants) {
                if (groups.contains(g.group)) break;
                if (!Checks.available(g, server)) continue;
                groups.add(g.group);
                out.add(g.key);
                break;
            }
        }
        return out;
    }

    /** Goal ids that can't appear on a board in this game, with the reason. */
    public static Map<String, String> unavailable(MinecraftServer server) {
        load();
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Goal>> e : byId.entrySet()) {
            boolean any = false;
            for (Goal g : e.getValue()) any |= Checks.available(g, server);
            if (!any) out.put(e.getKey(), Checks.whyUnavailable(e.getValue().get(0), server));
        }
        return out;
    }
}
