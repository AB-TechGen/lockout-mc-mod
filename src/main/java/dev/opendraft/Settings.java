package dev.opendraft;

import dev.opendraft.net.Relay;
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
    /** Extra minutes when the time limit ends in a tie; whoever pulls ahead first wins. 0 means a tie is a draw. */
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
    /**
     * How many of your newest OpenDraft match worlds to keep; older ones are deleted. 0 keeps them all.
     * It's about your own saves folder, so each player sets their own and it isn't shared with the lobby.
     */
    public static int keepWorlds = 5;
    /**
     * How big the opened board is, in percent of its normal size (50 to 300); it never grows past what fits on the
     * screen. Each player sets their own, like keepWorlds, and it isn't shared with the lobby.
     */
    public static int boardScale = 100;
    public static final int MIN_BOARD_SCALE = 50, MAX_BOARD_SCALE = 300;
    /**
     * Chat typed in a world goes to everyone in the lobby, and theirs shows in yours. Off keeps your chat in your own
     * world and hides theirs. Each player sets their own.
     */
    public static boolean playerChat = true;
    /** What the lobby's button cycles through. */
    private static final int[] KEEP_WORLDS_CHOICES = {1, 2, 3, 5, 10, 20, 50, 0};
    /** What the lobby screen last used, so it's filled in next time. */
    public static String name = "";
    public static String address = "";
    public static int port = 25570;
    /** Other relays than the built-in ones (comma-separated), for running your own. Empty uses the built-in ones. */
    public static String relay = "";
    /**
     * What gets this game's place back in the lobby it was last in, if the game closed or crashed: the address
     * joined, the name asked for, the name given, the secret sent with it and when it was last connected.
     */
    public static String rejoinAddress = "", rejoinAsked = "", rejoinName = "", rejoinToken = "";
    public static long rejoinTime;

    private Settings() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("opendraft.properties");
    }

    public static void load() {
        Path f = file();
        if (!Files.exists(f)) return;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(f)) {
            p.load(r);
        } catch (IOException e) {
            OpenDraftClient.LOG.warn("Could not read {}", f, e);
            return;
        }
        apply(p::getProperty);
        relay = text(p.getProperty("relay"));
        Relay.url = relay.isEmpty() ? Relay.DEFAULT_URL : relay;
        rejoinAddress = text(p.getProperty("rejoinAddress"));
        rejoinAsked = text(p.getProperty("rejoinAsked"));
        rejoinName = text(p.getProperty("rejoinName"));
        rejoinToken = text(p.getProperty("rejoinToken"));
        try {
            rejoinTime = Long.parseLong(text(p.getProperty("rejoinTime")));
        } catch (NumberFormatException e) {
            rejoinTime = 0;
        }
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
        keepWorlds = clamp(get.apply("keepWorlds"), keepWorlds, 0, 1000);
        boardScale = clamp(get.apply("boardScale"), boardScale, MIN_BOARD_SCALE, MAX_BOARD_SCALE);
        playerChat = !"false".equals(text(get.apply("playerChat")));
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
        p.setProperty("keepWorlds", String.valueOf(keepWorlds));
        p.setProperty("boardScale", String.valueOf(boardScale));
        p.setProperty("playerChat", String.valueOf(playerChat));
        if (!relay.isEmpty()) p.setProperty("relay", relay);
        if (rejoinTime > 0) {
            p.setProperty("rejoinAddress", rejoinAddress);
            p.setProperty("rejoinAsked", rejoinAsked);
            p.setProperty("rejoinName", rejoinName);
            p.setProperty("rejoinToken", rejoinToken);
            p.setProperty("rejoinTime", String.valueOf(rejoinTime));
        }
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

    /** The next choice for the lobby's "old worlds" button, after the current one, wrapping round. */
    public static int nextKeepWorlds() {
        for (int i = 0; i < KEEP_WORLDS_CHOICES.length; i++) {
            if (KEEP_WORLDS_CHOICES[i] == keepWorlds) return KEEP_WORLDS_CHOICES[(i + 1) % KEEP_WORLDS_CHOICES.length];
        }
        return KEEP_WORLDS_CHOICES[0]; // a number typed into the config: start the cycle over
    }

    /** "keep newest 5" or "keep all". */
    public static String keepWorldsText() {
        return keepWorlds <= 0 ? "keep all" : "keep newest " + keepWorlds;
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
        out.add("Old match worlds: " + keepWorldsText() + " (only on this computer)");
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
