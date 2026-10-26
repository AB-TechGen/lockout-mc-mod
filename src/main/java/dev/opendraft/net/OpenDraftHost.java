package dev.opendraft.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The synchronizer. It only tracks the bingo board; nobody's world goes through it.
 * Every player, including whoever runs it, connects over TCP. It is the only judge
 * of who claimed what, runs the draft and keeps the match clock. It has no Minecraft
 * dependencies, so it runs either inside a player's game ({@code /opendraft host}) or on
 * its own ({@link SyncServerMain}).
 */
public final class OpenDraftHost {
    private volatile int port;
    private final String modVersion;
    private final Consumer<String> log;
    private final Random random = new Random();
    private ServerSocket server;
    private ScheduledExecutorService clock;
    private volatile boolean closed;
    private volatile PortOpener router;
    private volatile Relay.Bridge relay;
    private volatile Thread routerCleanup;
    private volatile Reach reach;

    /**
     * Ping adjustment. The synchronizer times how long each player's messages take to reach it and
     * judges a close claim by when it was probably made, not when it arrived. A claim only waits as
     * long as another player's message could still be on its way (the difference in their lag), and
     * lag is only made up for up to this many ms each way.
     */
    static final long MAX_ONE_WAY_MS = 300;
    /** Each player's smoothed round trip in ms, from PING and PONG. Written on the players' own threads. */
    private final Map<String, Double> roundTrip = new ConcurrentHashMap<>();

    // Everything below is guarded by this.
    private final Map<String, LineChannel> players = new LinkedHashMap<>();
    /** Each player's colour, kept while they own anything on the board so it never changes mid-game. */
    private final Map<String, Integer> colors = new HashMap<>();
    private Long seed; // null until a player joins from a world or the lobby starts a match, unless fixed up front

    // The lobby: the leader's settings (shown to everyone) and the match being loaded.
    private String settings = "";
    private int matchId;
    private boolean loading; // between MATCH and GO, while everyone creates their world
    private long loadDeadline;
    private final Set<String> ready = new HashSet<>();

    // The match. startedAt is when the clock starts, after the lookout, so it can be in the future.
    private int boardId;
    private String[] keys;
    private String[] owners;
    private long startedAt;
    private int limitSec;
    private int overtimeSec;
    private boolean overtime;
    private boolean ended;
    private final Set<String> forfeited = new LinkedHashSet<>();
    private final Set<String> drawVotes = new LinkedHashSet<>();
    /** Claims waiting out the ping window, by goal. The first one for a goal schedules {@link #resolveClaim}. */
    private final Map<Integer, List<PendingClaim>> pending = new HashMap<>();

    /** @param made when the claim was probably made: when it arrived minus the player's one-way lag (epoch ms) */
    private record PendingClaim(String name, long made) {
    }

    // The draft, while one is running.
    private Draft draft;
    private int draftId;

    /** @param seed the required world seed, or null to adopt the first player's seed (or the lobby's) */
    public OpenDraftHost(int port, String modVersion, Long seed, Consumer<String> log) {
        this.port = port;
        this.modVersion = modVersion;
        this.seed = seed;
        this.log = log;
    }

    public int port() {
        return port;
    }

    public void open() throws IOException {
        ServerSocket s = new ServerSocket();
        try {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            s.close();
            throw e;
        }
        server = s;
        port = s.getLocalPort();
        Thread accept = new Thread(this::acceptLoop, "OpenDraft host accept");
        accept.setDaemon(true);
        accept.start();
        clock = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "OpenDraft host clock");
            t.setDaemon(true);
            return t;
        });
        clock.scheduleAtFixedRate(this::tick, 200, 200, TimeUnit.MILLISECONDS);
        clock.scheduleAtFixedRate(this::pingAll, 2, 2, TimeUnit.SECONDS);
    }

    /** Opens on the port given, or on any free one if that's taken. Players join with a code, so the number doesn't matter. */
    public void openAnyPort() throws IOException {
        try {
            open();
        } catch (IOException e) {
            port = 0;
            open();
        }
    }

    /**
     * Who can join and with what code, once {@link #shareOnline} has worked it out.
     * @param anywhere true when players on other networks should be able to connect
     */
    public record Reach(String code, boolean anywhere, String note) {
    }

    /** Null until {@link #shareOnline} finishes. */
    public Reach reach() {
        return reach;
    }

    /**
     * In the background, opens a lobby on the relay, or if there's no relay, asks the router to
     * open the port, and works out the code players join with. {@code done} runs on that thread.
     */
    public void shareOnline(Consumer<Reach> done) {
        Thread t = new Thread(() -> {
            if (Relay.available()) {
                try {
                    Relay.Bridge b = Relay.host(port, log, () -> {
                        Reach was = reach;
                        if (!closed && was != null) reach = new Reach(was.code(), false, "Lost the connection to the relay, so"
                                + " players can't join with this code anymore. Leave the lobby and create it again.");
                    });
                    synchronized (this) {
                        if (closed) {
                            b.close();
                            return;
                        }
                        relay = b;
                    }
                    log.accept("relay lobby " + b.code());
                    Reach result = new Reach(b.code(), true, "Friends anywhere can join with this code.");
                    reach = result;
                    done.accept(result);
                    return;
                } catch (IOException e) {
                    log.accept("relay unavailable (" + e.getMessage() + "), asking the router instead");
                }
            }
            PortOpener r = PortOpener.open(port);
            if (r.opened()) {
                // Take the forwarding off the router if the game quits without closing the lobby.
                Thread cleanup = new Thread(r::close, "OpenDraft router cleanup");
                synchronized (this) {
                    if (closed) {
                        r.close();
                        return;
                    }
                    router = r;
                    routerCleanup = cleanup;
                    Runtime.getRuntime().addShutdownHook(cleanup);
                }
            }
            String lan = r.localIp != null ? r.localIp : Invite.lanAddress();
            String internet = r.externalIp != null && Invite.isPublic(r.externalIp) ? r.externalIp : null;
            Reach result;
            if (r.opened() && internet != null) {
                result = new Reach(Invite.encode(lan, internet, port), true, "Friends anywhere can join with this code.");
            } else if (r.opened()) {
                result = new Reach(Invite.encode(lan, null, port), false, "Only players on your network can join: your connection is behind"
                        + " another network (often your internet provider's). See \"Playing over the internet\" in the README.");
            } else {
                if (internet == null) internet = PortOpener.publicAddress();
                result = new Reach(Invite.encode(lan, internet, port), false, "Only players on your network can join for now: your router"
                        + " didn't open port " + port + " by itself. Turn on UPnP in the router's settings or forward the port"
                        + " (see \"Playing over the internet\" in the README).");
            }
            log.accept(r.opened() ? "router opened port " + port + " (internet address " + r.externalIp + ")" : "router didn't open the port: " + r.problem);
            if (closed) return;
            reach = result;
            done.accept(result);
        }, "OpenDraft router");
        t.setDaemon(true);
        t.start();
    }

    public void close() {
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        PortOpener r;
        Thread cleanup;
        Relay.Bridge b;
        synchronized (this) {
            closed = true;
            b = relay;
            relay = null;
            r = router;
            cleanup = routerCleanup;
            router = null;
            routerCleanup = null;
        }
        if (b != null) b.close();
        if (r != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(cleanup);
            } catch (IllegalStateException ignored) {
                // Already shutting down; the hook takes the forwarding off.
            }
            Thread off = new Thread(r::close, "OpenDraft router cleanup");
            off.setDaemon(true);
            off.start();
        }
        if (clock != null) clock.shutdownNow();
        synchronized (this) {
            for (LineChannel c : players.values()) c.close();
            players.clear();
        }
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                Thread t = new Thread(() -> handle(s), "OpenDraft host client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!server.isClosed()) log.accept("accept failed: " + e);
            }
        }
    }

    private void handle(Socket socket) {
        LineChannel ch;
        try {
            socket.setSoTimeout(15_000); // only for the handshake
            ch = new LineChannel(socket);
        } catch (IOException e) {
            return;
        }
        String name = null;
        try {
            String hello = ch.readLine();
            String[] p = hello == null ? new String[0] : hello.split(" ");
            if (p.length != 5 || !p[0].equals("HELLO")) {
                ch.send("REJECT Not an OpenDraft client.");
                return;
            }
            if (!p[2].equals(modVersion)) {
                int age = compareVersions(p[2], modVersion);
                ch.send(age < 0 ? "REJECT This lobby needs OpenDraft " + modVersion + " and you have " + p[2]
                        + ". Download the latest from the Releases page."
                        : age > 0 ? "REJECT The host has an older OpenDraft (" + modVersion + ") than you (" + p[2]
                        + "). They need to update."
                        : "REJECT Mod version mismatch: host has " + modVersion + ", you have " + p[2] + ".");
                return;
            }
            if (!p[1].equals(String.valueOf(Protocol.VERSION))) {
                ch.send("REJECT Your OpenDraft download doesn't match the host's, though both say " + modVersion
                        + ". Everyone needs the same one.");
                return;
            }
            Long theirSeed;
            try {
                theirSeed = p[3].equals("-") ? null : Long.valueOf(p[3]); // "-" joins from the title screen
            } catch (NumberFormatException e) {
                ch.send("REJECT Bad seed.");
                return;
            }
            socket.setSoTimeout(0);
            name = join(ch, Protocol.sanitizeName(p[4]), theirSeed);
            if (name == null) return;
            for (String line; (line = ch.readLine()) != null; ) {
                // Taken before waiting for the lock, so time spent behind another player's line isn't counted as lag.
                long arrived = System.currentTimeMillis();
                long arrivedNanos = System.nanoTime();
                try {
                    if (line.startsWith("PONG ")) {
                        pong(name, line, arrivedNanos);
                        continue;
                    }
                    onLine(name, line, arrived);
                } catch (RuntimeException e) {
                    log.accept("bad line from " + name + ": " + line + " (" + e + ")");
                }
            }
        } catch (IOException ignored) {
            // Disconnected.
        } finally {
            ch.close();
            if (name != null) leave(name, ch);
        }
    }

    private synchronized String join(LineChannel ch, String wanted, Long theirSeed) {
        if (theirSeed == null) {
            // From the lobby: the seed comes with the next match.
        } else if (seed == null) {
            seed = theirSeed;
            log.accept("seed locked to " + seed + " by the first player");
        } else if (!seed.equals(theirSeed)) {
            ch.send("REJECT World seed mismatch: this game uses seed " + seed + ", yours is " + theirSeed
                    + ". Create a new world with that seed.");
            return null;
        }
        String name = wanted;
        for (int i = 2; players.containsKey(name); i++) {
            String suffix = String.valueOf(i);
            name = wanted.substring(0, Math.min(wanted.length(), 16 - suffix.length())) + suffix;
        }
        players.put(name, ch);
        if (!colors.containsKey(name)) {
            int c = 0;
            while (colors.containsValue(c)) c++;
            colors.put(name, c);
        }
        log.accept(name + " joined from " + ch.remote());
        ch.send("WELCOME " + name);
        ch.send("PING " + System.nanoTime()); // so their lag is known before their first claim
        broadcast(playersLine());
        broadcastExcept(ch, "INFO " + name + " joined.");
        // Catch the newcomer up on whatever is happening.
        if (!settings.isEmpty()) ch.send("SETTINGS " + settings);
        boolean matchOn = loading || draft != null || (keys != null && !ended);
        if (matchId > 0 && matchOn) ch.send("MATCH " + matchId + " " + seed);
        if (draft != null) {
            ch.send("DRAFTPOOL " + draftId + " " + String.join(" ", draft.pool));
            for (int i = 0; i < draft.pickedBy.length; i++) {
                if (draft.pickedBy[i] != null) ch.send("PICKED " + i + " " + draft.pickedBy[i]);
            }
            ch.send(draft.turnLine());
        } else if (keys != null) {
            ch.send("BOARD " + boardId + " " + String.join(" ", keys));
            ch.send(startLine());
            for (int i = 0; i < owners.length; i++) {
                if (owners[i] != null) ch.send("CLAIMED " + i + " " + owners[i]);
            }
            for (String f : forfeited) ch.send("FORFEITED " + f);
            if (!drawVotes.isEmpty()) ch.send(drawLine());
            if (overtime && !ended) ch.send("OVERTIME");
            if (ended) ch.send("END " + winnerOrDash());
        }
        return name;
    }

    private synchronized void leave(String name, LineChannel ch) {
        if (players.get(name) != ch) return;
        players.remove(name);
        roundTrip.remove(name);
        log.accept(name + " left");
        forgetColors();
        broadcast(playersLine());
        broadcast("INFO " + name + " left.");
        if (draft != null && name.equals(draft.current())) draft.nextTurn();
        ready.remove(name);
        if (loading && !players.isEmpty() && ready.containsAll(players.keySet())) go();
        if (drawVotes.remove(name)) {
            broadcast(drawLine());
            checkDraw();
        }
    }

    private synchronized void onLine(String name, String line, long arrived) {
        String[] p = line.split(" ");
        switch (p[0]) {
            case "NEWBOARD" -> {
                // NEWBOARD <limitSec> <overtimeSec> <lookoutSec> <key>...
                if (!mayStart(name) || p.length < 4) return;
                List<String> boardKeys = Arrays.asList(p).subList(4, p.length);
                if (!validBoard(name, boardKeys)) return;
                draft = null;
                broadcast("INFO " + name + " started a new board.");
                startMatch(boardKeys, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
            }
            case "DRAFT" -> {
                // DRAFT <limitSec> <overtimeSec> <lookoutSec> <size> <turnSec> <poolKey>...
                if (!mayStart(name) || p.length < 6) return;
                int size = Integer.parseInt(p[4]);
                List<String> pool = Arrays.asList(p).subList(6, p.length);
                if (size < Protocol.MIN_BOARD || size > Protocol.MAX_BOARD || pool.size() < size || pool.size() > 64) {
                    players.get(name).send("INFO A draft needs " + Protocol.MIN_BOARD + " to " + Protocol.MAX_BOARD
                            + " goals and a pool at least that big.");
                    return;
                }
                keys = null;
                pending.clear();
                forgetColors();
                draftId++;
                draft = new Draft(pool, size, Integer.parseInt(p[5]), Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                        Integer.parseInt(p[3]), new ArrayList<>(players.keySet()));
                log.accept(name + " started a draft for " + size + " goals from " + pool.size());
                broadcast("INFO " + name + " started a draft. Pick goals when it's your turn.");
                broadcast("DRAFTPOOL " + draftId + " " + String.join(" ", pool));
                draft.startTurn();
            }
            case "PICK" -> {
                if (draft == null || p.length != 3 || Integer.parseInt(p[1]) != draftId) return;
                draft.pick(name, Integer.parseInt(p[2]));
            }
            case "SETTINGS" -> {
                // The leader's lobby settings, shown to everyone. Opaque to the synchronizer.
                if (!name.equals(leader()) || line.length() > 1000) return;
                settings = line.length() > "SETTINGS ".length() ? line.substring("SETTINGS ".length()) : "";
                broadcast("SETTINGS " + settings);
            }
            case "MATCH" -> {
                // MATCH <seed>: everyone makes a new world with this seed; GO once they're all in.
                if (p.length != 2 || !mayStart(name)) return;
                seed = Long.parseLong(p[1]);
                matchId++;
                draft = null;
                keys = null;
                pending.clear();
                forgetColors();
                ended = false;
                overtime = false;
                forfeited.clear();
                drawVotes.clear();
                ready.clear();
                loading = true;
                loadDeadline = System.currentTimeMillis() + 180_000;
                log.accept(name + " started match " + matchId + " on seed " + seed);
                broadcast("MATCH " + matchId + " " + seed);
            }
            case "READY" -> {
                if (p.length != 2 || !loading || Integer.parseInt(p[1]) != matchId) return;
                ready.add(name);
                if (ready.containsAll(players.keySet())) go();
            }
            case "FORFEIT" -> {
                if (keys == null || ended || !forfeited.add(name)) return;
                log.accept(name + " forfeited");
                broadcast("FORFEITED " + name);
                if (drawVotes.remove(name)) broadcast(drawLine());
                List<String> left = contenders();
                if (left.size() <= 1) end(left.isEmpty() ? "-" : left.get(0));
                else checkDraw();
            }
            case "DRAW" -> {
                if (keys == null || ended || forfeited.contains(name) || !drawVotes.add(name)) return;
                broadcast(drawLine());
                checkDraw();
            }
            case "NODRAW" -> {
                if (drawVotes.isEmpty()) return;
                drawVotes.clear();
                broadcast(drawLine());
                broadcast("INFO " + name + " turned down the draw.");
            }
            case "CHAT" -> {
                if (line.length() <= "CHAT ".length() || line.length() > 260) return;
                broadcast("CHAT " + name + " " + line.substring("CHAT ".length()));
            }
            case "CLAIM" -> {
                if (p.length != 3 || forfeited.contains(name)) return;
                int board = Integer.parseInt(p[1]);
                int idx = Integer.parseInt(p[2]);
                if (keys == null || ended || board != boardId || idx < 0 || idx >= owners.length || owners[idx] != null) {
                    return;
                }
                if (arrived < startedAt) return; // still in the lookout
                claim(name, idx, arrived);
            }
            case "ADV" -> {
                // ADV <frame> <title...>: someone earned an advancement; tell everyone else.
                if (p.length < 3) return;
                String rest = line.substring("ADV ".length());
                broadcastExcept(players.get(name), "ADV " + name + " " + rest);
            }
            default -> {
            }
        }
    }

    // ---- ping adjustment ------------------------------------------------------------------------

    private synchronized void pingAll() {
        long now = System.nanoTime();
        for (LineChannel c : players.values()) c.send("PING " + now);
    }

    /** PONG &lt;the number from PING&gt;: one round trip, smoothed so a single slow packet doesn't swing it. */
    private void pong(String name, String line, long arrivedNanos) {
        long ms = (arrivedNanos - Long.parseLong(line.substring("PONG ".length()).trim())) / 1_000_000;
        if (ms < 0 || ms > 10_000) return;
        roundTrip.merge(name, (double) ms, (old, now) -> old * 0.7 + now * 0.3);
    }

    /** How long this player's messages take to get here: half their round trip, capped. 0 until it's been measured. */
    private long oneWay(String name) {
        Double rtt = roundTrip.get(name);
        return rtt == null ? 0 : Math.min(MAX_ONE_WAY_MS, Math.round(rtt / 2));
    }

    /** A claim has arrived for a goal nobody owns yet. */
    private void claim(String name, int idx, long arrived) {
        long mine = oneWay(name);
        long made = arrived - mine;
        List<PendingClaim> waiting = pending.get(idx);
        if (waiting != null) {
            // Someone's claim for this goal is already waiting out its window: this one is judged with it.
            if (waiting.stream().noneMatch(c -> c.name().equals(name))) waiting.add(new PendingClaim(name, made));
            return;
        }
        // A claim someone else made before this one can still be on its way, for as long as their lag is longer.
        long window = 0;
        for (String other : players.keySet()) {
            if (!other.equals(name) && !forfeited.contains(other)) window = Math.max(window, oneWay(other) - mine);
        }
        if (window <= 0 || clock == null) {
            applyClaim(idx, name);
            return;
        }
        waiting = new ArrayList<>();
        waiting.add(new PendingClaim(name, made));
        pending.put(idx, waiting);
        int forBoard = boardId;
        clock.schedule(() -> resolveClaim(forBoard, idx, Long.MAX_VALUE), window, TimeUnit.MILLISECONDS);
    }

    /**
     * A goal's window is over: whoever probably claimed it first gets it. Claims made after {@code cutoff}
     * (the end of the time limit) don't count; with a cutoff, claims are left waiting if none qualifies.
     */
    private synchronized void resolveClaim(int forBoard, int idx, long cutoff) {
        List<PendingClaim> waiting = pending.get(idx);
        if (waiting == null) return;
        if (forBoard != boardId || keys == null || ended || owners[idx] != null) {
            pending.remove(idx);
            return;
        }
        PendingClaim best = null;
        for (PendingClaim c : waiting) {
            if (forfeited.contains(c.name()) || c.made() > cutoff) continue;
            if (best == null || c.made() < best.made()) best = c;
        }
        if (best == null) {
            if (cutoff == Long.MAX_VALUE) pending.remove(idx);
            return;
        }
        pending.remove(idx);
        PendingClaim arrivedFirst = waiting.get(0);
        if (!arrivedFirst.name().equals(best.name())) {
            long by = arrivedFirst.made() - best.made();
            log.accept("goal " + idx + ": " + best.name() + " ahead of " + arrivedFirst.name() + " by " + by + " ms after ping");
            broadcast("INFO " + best.name() + " got there " + by + " ms before " + arrivedFirst.name() + " once ping is taken into account.");
        }
        applyClaim(idx, best.name());
    }

    private void resolveClaimsUpTo(long cutoff) {
        for (int idx : new ArrayList<>(pending.keySet())) resolveClaim(boardId, idx, cutoff);
    }

    /** Gives the goal to {@code name} and tells everyone. */
    private void applyClaim(int idx, String name) {
        owners[idx] = name;
        broadcast("CLAIMED " + idx + " " + name);
        if (!drawVotes.isEmpty()) {
            // A new claim changes the game, so an open draw offer lapses.
            drawVotes.clear();
            broadcast(drawLine());
        }
        checkEnd();
    }

    /** Compares versions like 0.7.0 and 0.8.0 number by number; parts that aren't numbers count as 0. */
    static int compareVersions(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int c = Integer.compare(number(x, i), number(y, i));
            if (c != 0) return c;
        }
        return 0;
    }

    private static int number(String[] parts, int i) {
        try {
            return i < parts.length ? Integer.parseInt(parts[i].replaceAll("[^0-9].*", "")) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Anyone may start when nothing is running; during a game or draft only the lobby leader may restart. */
    private boolean mayStart(String from) {
        boolean running = loading || draft != null || (keys != null && !ended);
        String leader = leader();
        if (running && !from.equals(leader)) {
            players.get(from).send("INFO A game is running. Only " + leader + " can restart it.");
            return false;
        }
        return true;
    }

    /** PLAYERS &lt;name&gt;:&lt;colour&gt;... in join order. */
    private String playersLine() {
        StringBuilder b = new StringBuilder("PLAYERS");
        for (String p : players.keySet()) b.append(' ').append(p).append(':').append(colors.get(p));
        return b.toString();
    }

    /** Frees the colours of players who left and own nothing on the board or in the draft any more. */
    private void forgetColors() {
        Set<String> keep = new HashSet<>(players.keySet());
        if (keys != null && owners != null) for (String o : owners) if (o != null) keep.add(o);
        if (draft != null) for (String o : draft.pickedBy) if (o != null) keep.add(o);
        colors.keySet().retainAll(keep);
    }

    /** The first player in the lobby (join order) runs it: settings, starting and restarting. */
    private String leader() {
        return players.isEmpty() ? null : players.keySet().iterator().next();
    }

    private void go() {
        loading = false;
        log.accept("match " + matchId + ": everyone is in");
        broadcast("GO " + matchId);
    }

    /** Players who can still win: everyone with a claim or a connection, minus those who forfeited. */
    private List<String> contenders() {
        Set<String> all = new LinkedHashSet<>(players.keySet());
        if (owners != null) for (String o : owners) if (o != null) all.add(o);
        all.removeAll(forfeited);
        return new ArrayList<>(all);
    }

    /** A draw happens when every connected player still in the game has voted for it. */
    private void checkDraw() {
        if (drawVotes.isEmpty() || keys == null || ended) return;
        for (String p : players.keySet()) {
            if (!forfeited.contains(p) && !drawVotes.contains(p)) return;
        }
        broadcast("INFO Everyone agreed to a draw.");
        end("-");
    }

    private String drawLine() {
        return drawVotes.isEmpty() ? "DRAWVOTE" : "DRAWVOTE " + String.join(" ", drawVotes);
    }

    private boolean validBoard(String from, List<String> boardKeys) {
        if (boardKeys.size() < Protocol.MIN_BOARD || boardKeys.size() > Protocol.MAX_BOARD
                || boardKeys.stream().anyMatch(k -> k.isEmpty() || k.length() > 96)) {
            players.get(from).send("INFO Board must have " + Protocol.MIN_BOARD + " to " + Protocol.MAX_BOARD + " goals.");
            return false;
        }
        return true;
    }

    private void startMatch(List<String> boardKeys, int limit, int overtimeLength, int lookout) {
        boardId++;
        pending.clear();
        keys = boardKeys.toArray(new String[0]);
        owners = new String[keys.length];
        forgetColors();
        limitSec = Math.max(0, limit);
        overtimeSec = Math.max(0, overtimeLength);
        startedAt = System.currentTimeMillis() + Math.max(0, lookout) * 1000L;
        overtime = false;
        ended = false;
        loading = false;
        forfeited.clear();
        drawVotes.clear();
        log.accept("board " + boardId + ": " + keys.length + " goals, limit " + limitSec + "s, overtime "
                + overtimeSec + "s, lookout " + lookout + "s");
        broadcast("BOARD " + boardId + " " + String.join(" ", keys));
        broadcast(startLine());
    }

    /** START &lt;elapsedMillis&gt; &lt;limitSec&gt; &lt;overtimeSec&gt;; elapsed is negative during the lookout. */
    private String startLine() {
        return "START " + (System.currentTimeMillis() - startedAt) + " " + limitSec + " " + overtimeSec;
    }

    public synchronized int playerCount() {
        return players.size();
    }

    public synchronized Long seed() {
        return seed;
    }

    // ---- the clock: draft turn timeouts and the match time limit ---------------------------------

    private synchronized void tick() {
        try {
            long now = System.currentTimeMillis();
            if (loading && now >= loadDeadline && !players.isEmpty()) {
                broadcast("INFO Not everyone finished loading; starting anyway.");
                go();
            }
            if (draft != null && now >= draft.deadline) draft.timeout();
            if (keys == null || ended || limitSec <= 0) return;
            long elapsed = now - startedAt;
            if (!overtime && elapsed >= limitSec * 1000L) {
                resolveClaimsUpTo(startedAt + limitSec * 1000L); // claims made in time still count
                if (ended) return;
                String w = winnerOrDash();
                if (!w.equals("-") || overtimeSec <= 0) {
                    end(w);
                } else {
                    overtime = true;
                    log.accept("time's up with a tie, overtime");
                    broadcast("OVERTIME");
                }
            } else if (overtime && elapsed >= (limitSec + overtimeSec) * 1000L) {
                resolveClaimsUpTo(startedAt + (limitSec + overtimeSec) * 1000L);
                if (!ended) end(winnerOrDash());
            }
        } catch (RuntimeException e) {
            log.accept("clock error: " + e);
        }
    }

    private void checkEnd() {
        if (overtime) {
            // Sudden death: the first claim that puts someone ahead wins. With every goal gone, the tie stands.
            String w = winnerOrDash();
            boolean anyLeft = false;
            for (String o : owners) anyLeft |= o == null;
            if (!w.equals("-") || !anyLeft) end(w);
            return;
        }
        Map<String, Integer> score = new LinkedHashMap<>();
        int unclaimed = 0;
        for (String o : owners) {
            if (o == null) unclaimed++;
            else if (!forfeited.contains(o)) score.merge(o, 1, Integer::sum);
        }
        // Players who are connected but have no claims still count as rivals; those who forfeited don't.
        for (String p : players.keySet()) if (!forfeited.contains(p)) score.putIfAbsent(p, 0);
        List<Integer> sorted = new ArrayList<>(score.values());
        sorted.sort((a, b) -> b - a);
        int best = sorted.isEmpty() ? 0 : sorted.get(0);
        int second = sorted.size() > 1 ? sorted.get(1) : 0;
        boolean solo = sorted.size() <= 1;
        if (unclaimed == 0 || (!solo && best > second + unclaimed)) end(winnerOrDash());
    }

    private void end(String winner) {
        ended = true;
        log.accept("game over, winner " + winner);
        broadcast("END " + winner);
    }

    /** The single player with the most goals, or "-" when the top is tied (including 0-0). Forfeits don't count. */
    private String winnerOrDash() {
        Map<String, Integer> score = new LinkedHashMap<>();
        for (String o : owners) if (o != null && !forfeited.contains(o)) score.merge(o, 1, Integer::sum);
        String winner = "-";
        int best = -1;
        boolean tie = false;
        for (Map.Entry<String, Integer> e : score.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                winner = e.getKey();
                tie = false;
            } else if (e.getValue() == best) {
                tie = true;
            }
        }
        return tie ? "-" : winner;
    }

    private void broadcast(String line) {
        for (LineChannel c : players.values()) c.send(line);
    }

    private void broadcastExcept(LineChannel skip, String line) {
        for (LineChannel c : players.values()) if (c != skip) c.send(line);
    }

    // ---- the draft -------------------------------------------------------------------------------

    /**
     * Players take turns choosing goals from a pool in snake order (A B C, C B A, A B C...), so
     * the players at either end of the order pick twice in a row and nobody gets ahead. With two
     * players that's 1, 2, 2, 2... picks a turn. A goal can only be picked once. When a turn runs
     * out of time the remaining picks are made at random.
     */
    private final class Draft {
        final List<String> pool;
        final String[] pickedBy;
        final List<Integer> order = new ArrayList<>();
        final int size, turnSec, limit, overtimeLength, lookout;
        final List<String> turnOrder;
        String turnPlayer;
        int picksLeft;
        long deadline = Long.MAX_VALUE;

        Draft(List<String> pool, int size, int turnSec, int limit, int overtimeLength, int lookout, List<String> turnOrder) {
            this.pool = new ArrayList<>(pool);
            this.pickedBy = new String[pool.size()];
            this.size = size;
            this.turnSec = Math.max(5, turnSec);
            this.limit = limit;
            this.overtimeLength = overtimeLength;
            this.lookout = lookout;
            this.turnOrder = turnOrder;
        }

        String current() {
            return turnPlayer;
        }

        /** Whose pick the k-th pick is, as a position in the turn order: forwards, then backwards, and so on. */
        int snake(int k, int n) {
            int pos = k % n;
            return (k / n) % 2 == 0 ? pos : n - 1 - pos;
        }

        void startTurn() {
            nextTurn();
        }

        void nextTurn() {
            turnOrder.retainAll(players.keySet());
            if (turnOrder.isEmpty()) {
                draft = null;
                return;
            }
            int k = order.size(), n = turnOrder.size();
            int who = snake(k, n);
            int run = 1;
            while (k > 0 && run < 2 && snake(k + run, n) == who) run++; // a lone player still picks 1, then 2s
            turnPlayer = turnOrder.get(who);
            picksLeft = Math.min(run, size - k);
            deadline = System.currentTimeMillis() + turnSec * 1000L;
            broadcast(turnLine());
        }

        String turnLine() {
            return "TURN " + current() + " " + picksLeft + " " + Math.max(0, deadline - System.currentTimeMillis());
        }

        void pick(String who, int index) {
            if (!who.equals(current()) || index < 0 || index >= pool.size() || pickedBy[index] != null) return;
            pickedBy[index] = who;
            order.add(index);
            broadcast("PICKED " + index + " " + who);
            if (order.size() >= size) {
                finish();
            } else if (--picksLeft <= 0) {
                nextTurn();
            } else {
                broadcast(turnLine());
            }
        }

        void timeout() {
            String who = current();
            List<Integer> free = new ArrayList<>();
            for (int i = 0; i < pickedBy.length; i++) if (pickedBy[i] == null) free.add(i);
            Collections.shuffle(free, random);
            int n = picksLeft;
            broadcast("INFO " + who + " ran out of time; picking at random.");
            for (int i = 0; i < n && i < free.size() && draft == this; i++) pick(who, free.get(i));
        }

        void finish() {
            List<String> chosen = new ArrayList<>();
            for (int i : order) chosen.add(pool.get(i));
            // Shuffle so a cell's position doesn't reveal who picked it.
            Collections.shuffle(chosen, random);
            draft = null;
            broadcast("INFO Draft complete.");
            startMatch(chosen, limit, overtimeLength, lookout);
        }
    }

    @Override
    public synchronized String toString() {
        return "OpenDraftHost[port=" + port + ", players=" + players.keySet() + ", keys=" + Arrays.toString(keys) + "]";
    }
}
