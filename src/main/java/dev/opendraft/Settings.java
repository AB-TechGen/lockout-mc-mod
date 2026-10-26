package dev.opendraft;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * How the next game is dealt, saved in config/opendraft.properties. The lobby leader's copy is
 * what counts: it's shown to everyone in the lobby and used when the leader deals the board.
 */
public final class Settings {
    /** Goals on the board. */
    public static int size = 25;
    /** Time limit in minutes; 0 means play until someone can't be caught. */
    public static int minutes = 0;
    /** Extra minutes when the time limit ends in a tie; the next goal claimed wins. 0 means a tie is a draw. */
    public static int overtime = 0;
    /** Seconds everyone spends looking around from the air before the clock starts. */
    public static int lookout = 30;
    /** "random" deals the board straight away, "draft" lets players pick the goals in turns. */
    public static String mode = "random";
    /** Seconds each draft turn lasts before the remaining picks are made at random. */
    public static int turnSeconds = 30;
    /** The world seed for lobby matches as typed; blank means a random seed. */
    public static String seed = "";
    /** Structures and biomes a random seed must have near spawn, as SeedSearch keys separated by commas. */
    public static String filter = "";
    /** How close to spawn, in blocks, the filtered structures must be. */
    public static int radius = 300;
    /** What the lobby screen last used, so it's filled in next time. */
    public static String name = "";
    public static String address = "";
    public static int port = 25570;

    private Settings() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("opendraft.properties");
    }

    public static void load() {
        Path f = file();
        // Before the rename to OpenDraft the settings lived in lockout.properties.
        if (!Files.exists(f)) f = f.resolveSibling("lockout.properties");
        if (!Files.exists(f)) return;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(f)) {
            p.load(r);
        } catch (IOException e) {
            OpenDraftClient.LOG.warn("Could not read {}", f, e);
            return;
        }
        apply(p::getProperty);
    }

    private static void apply(java.util.function.Function<String, String> get) {
        size = clamp(get.apply("size"), size, 3, 25);
        minutes = clamp(get.apply("minutes"), minutes, 0, 600);
        overtime = clamp(get.apply("overtime"), overtime, 0, 120);
        lookout = clamp(get.apply("lookout"), lookout, 0, 120);
        turnSeconds = clamp(get.apply("turnSeconds"), turnSeconds, 5, 120);
        mode = "draft".equals(get.apply("mode")) ? "draft" : "random";
        seed = text(get.apply("seed"));
        filter = cleanFilter(get.apply("filter"));
        radius = clamp(get.apply("radius"), radius, 50, 2000);
        name = text(get.apply("name"));
        address = text(get.apply("address"));
        port = clamp(get.apply("port"), port, 1, 65535);
    }

    private static String text(String raw) {
        return raw == null ? "" : raw.trim();
    }

    public static void save() {
        Properties p = new Properties();
        p.putAll(values());
        p.setProperty("seed", seed);
        p.setProperty("name", name);
        p.setProperty("address", address);
        p.setProperty("port", String.valueOf(port));
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            try (Writer w = Files.newBufferedWriter(f)) {
                p.store(w, "OpenDraft settings");
            }
        } catch (IOException e) {
            OpenDraftClient.LOG.warn("Could not write {}", f, e);
        }
    }

    /** The settings everyone in the lobby sees (the seed stays with the leader until Start). */
    private static Map<String, String> values() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("size", String.valueOf(size));
        m.put("minutes", String.valueOf(minutes));
        m.put("overtime", String.valueOf(overtime));
        m.put("lookout", String.valueOf(lookout));
        m.put("mode", mode);
        m.put("turnSeconds", String.valueOf(turnSeconds));
        m.put("filter", filter);
        m.put("radius", String.valueOf(radius));
        return m;
    }

    /** As sent in a SETTINGS line: key=value pairs separated by spaces. A typed seed skips the filter. */
    public static String encode() {
        Map<String, String> m = values();
        if (!seed.isEmpty()) m.put("filter", "");
        StringBuilder b = new StringBuilder();
        m.forEach((k, v) -> b.append(b.isEmpty() ? "" : " ").append(k).append('=').append(v));
        return b.toString();
    }

    /** The structures and biomes the next random seed must have near spawn. */
    public static List<SeedSearch.Target> filterTargets() {
        return targets(filter);
    }

    private static List<SeedSearch.Target> targets(String keys) {
        List<SeedSearch.Target> out = new ArrayList<>();
        if (keys == null) return out;
        for (String k : keys.split(",")) {
            SeedSearch.Target t = SeedSearch.target(k.trim());
            if (t != null && !out.contains(t)) out.add(t);
        }
        return out;
    }

    /** Known keys only, in the order SeedSearch lists them. */
    public static String cleanFilter(String keys) {
        List<SeedSearch.Target> chosen = targets(keys);
        List<String> out = new ArrayList<>();
        for (SeedSearch.Target t : SeedSearch.TARGETS) if (chosen.contains(t)) out.add(t.key());
        return String.join(",", out);
    }

    public static Map<String, String> decode(String line) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String part : line.trim().split(" ")) {
            int eq = part.indexOf('=');
            if (eq > 0) m.put(part.substring(0, eq), part.substring(eq + 1));
        }
        return m;
    }

    private static int clamp(String raw, int fallback, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(raw.trim())));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** "25 goals (5x5)" or "12 goals". */
    public static String boardSize(int n) {
        int root = (int) Math.round(Math.sqrt(n));
        return n + " goals" + (root * root == n ? " (" + root + "x" + root + ")" : "");
    }

    /** "15 min + 5 min overtime", "15 min, no overtime" or "No time limit". */
    public static String timing(int minutes, int overtime) {
        if (minutes <= 0) return "No time limit";
        return minutes + " min" + (overtime > 0 ? " + " + overtime + " min overtime" : ", no overtime");
    }

    /** One line per setting, for chat and the lobby. Works on this game's settings or the leader's. */
    public static List<String> describe(Map<String, String> m) {
        int n = clamp(m.get("size"), 25, 3, 25);
        boolean draft = "draft".equals(m.get("mode"));
        List<String> out = new ArrayList<>();
        out.add("Board: " + boardSize(n) + ", " + (draft ? "drafted (" + clamp(m.get("turnSeconds"), 30, 5, 120) + "s turns)" : "random"));
        out.add("Time: " + timing(clamp(m.get("minutes"), 0, 0, 600), clamp(m.get("overtime"), 0, 0, 120)));
        out.add("Lookout: " + clamp(m.get("lookout"), 30, 0, 120) + "s");
        List<SeedSearch.Target> near = targets(m.get("filter"));
        if (!near.isEmpty()) {
            out.add("Near spawn (" + clamp(m.get("radius"), 300, 50, 2000) + " blocks): " + SeedSearch.labels(near));
        }
        return out;
    }

    public static List<String> describe() {
        List<String> out = describe(decode(encode()));
        out.add("Seed: " + (seed.isEmpty() ? "random" : seed));
        return out;
    }

    /** The seed for a new match: the typed one (a number, or text hashed like vanilla does), else random. */
    public static long matchSeed() {
        if (seed.isEmpty()) return new java.util.Random().nextLong();
        try {
            return Long.parseLong(seed);
        } catch (NumberFormatException e) {
            return seed.hashCode();
        }
    }
}
