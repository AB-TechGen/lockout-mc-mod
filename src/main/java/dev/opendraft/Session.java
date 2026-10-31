package dev.opendraft;

import dev.opendraft.goal.Goal;
import dev.opendraft.goal.Goals;
import dev.opendraft.net.OpenDraftConnection;
import dev.opendraft.net.OpenDraftHost;
import dev.opendraft.net.Protocol;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This game's view of the OpenDraft lobby and match: who is in the lobby, the leader's settings,
 * the draft, the board, who owns each goal and the clock. A session outlives worlds: players stay
 * in the lobby between matches. Updated from the network thread and read from the client and
 * integrated server threads, so fields are volatile and arrays are replaced, not edited.
 */
public final class Session {
    /** startMillis while a board has arrived but its START hasn't yet. */
    public static final long NOT_STARTED = Long.MAX_VALUE;
    /** The last part of the lookout, spent on the ground with a countdown. */
    public static final long GROUND_MILLIS = 5000;
    private static final int CHAT_LINES = 50;

    private static volatile Session current;
    /** Why the last session ended or failed, for the lobby screen. */
    public static volatile String lastStatus;

    /** The current connection, set before its first line is read. Replaced on each reconnect. */
    public volatile OpenDraftConnection connection;
    /** Non-null when this game is also running the synchronizer. */
    public final OpenDraftHost host;
    public final String address;
    /** This world's seed as sent in HELLO, or "-" from the lobby. */
    private final String helloSeed;
    /** The name typed in the lobby, before the synchronizer made it unique. */
    private final String asked;
    /** This game's secret for this lobby: with the same name, it gets our place back after a dropped connection. */
    private final String token;

    /** The name the synchronizer gave us (what we asked for until then). Reconnects ask for it again. */
    public volatile String me;
    public volatile boolean welcomed;
    /** How many times the synchronizer has welcomed us, so things told once (like where we are) are told again. */
    public volatile int welcomes;
    /** The connection dropped and the game is trying to get back in; everything here is kept meanwhile. */
    public volatile boolean reconnecting;
    /** When the connection dropped, while reconnecting. */
    private volatile long lostAt;
    /** The synchronizer turned us away (REJECT, which says why), so a dropped connection isn't worth reconnecting. */
    private volatile boolean rejected;
    /** The synchronizer said the lobby is closing (CLOSED), so there's nothing to reconnect to. */
    private volatile boolean lobbyClosed;
    /** Counts connection attempts, so only the newest connection closing counts. */
    private final AtomicInteger attempt = new AtomicInteger();
    /** Claims made while disconnected, sent once we're back. */
    private final List<String> outbox = new CopyOnWriteArrayList<>();
    private volatile long rejoinSavedAt;
    public volatile List<String> players = List.of();
    /** Players whose connection dropped; the synchronizer keeps their place for a few minutes. */
    public volatile Set<String> away = Set.of();
    /** Everyone's colour number from the synchronizer, kept for players who left so their claims keep it. */
    public volatile Map<String, Integer> colors = Map.of();
    /** Where each player is ("overworld", "nether", "end" or "lobby"), for the tab list. */
    public volatile Map<String, String> where = Map.of();
    /** The leader whose game is looking for a seed for the next match, or null. */
    public volatile String searchingBy;
    /** The leader's settings as the synchronizer last relayed them. */
    public volatile Map<String, String> lobbySettings = Map.of();
    public final List<String> chatLines = new CopyOnWriteArrayList<>();

    // The lobby match: everyone makes a world with matchSeed, and the board starts once all are in.
    public volatile int matchId;
    public volatile Long matchSeed;
    /** Tells this match's world apart from others on the same seed, so rejoining reopens the right one. */
    public volatile String matchKey;
    /** Between MATCH and the clock starting: players are held in the air while the others load. */
    public volatile boolean waitingForStart;
    public volatile int readySent;
    /** When READY went out, so the leader can start without the others after {@link #START_WITHOUT_MILLIS}. */
    public volatile long readySentAt;
    /** Whose world everyone is still waiting for, from the synchronizer. */
    public volatile List<String> stillLoading = List.of();
    /** GO arrived for this match: every world is loaded, so the view no longer has to be covered. */
    public volatile boolean everyoneLoaded;

    // The board. startMillis is when the clock starts (after the lookout), in this computer's time.
    public volatile int boardId;
    public volatile List<Goal> goals;
    public volatile String[] owners = new String[0];
    public volatile long startMillis = NOT_STARTED;
    public volatile int limitSec;
    public volatile int overtimeSec;
    public volatile boolean overtime;
    /** The board began with a lookout, so the HUD shows the countdown and "GO!". */
    public volatile boolean hadLookout;
    /** Chat was cleared for this board's start, or its start has passed; see OpenDraftClient.wipeChatAtStart. */
    public volatile boolean chatWiped;
    public volatile long endMillis;
    public volatile boolean ended;
    public volatile String winner;
    public volatile Set<String> forfeited = Set.of();
    /** Who has voted for a draw; empty when no draw is on offer. */
    public volatile List<String> drawVotes = List.of();
    /** Goals this game already claimed for the current board, so a claim is sent once. */
    public final Set<Integer> sent = ConcurrentHashMap.newKeySet();

    // The draft, while one is running (draftPool is null otherwise).
    public volatile int draftId;
    public volatile List<Goal> draftPool;
    public volatile String[] draftPicks = new String[0];
    public volatile String turnPlayer;
    public volatile int turnPicks;
    public volatile long turnDeadline;
    /** The leader is looking for a new seed: nobody picks, and the turn's time stays at pausedLeft. */
    public volatile boolean draftPaused;
    public volatile long pausedLeft;
    /** The board should open as soon as no other screen is (the draft started, or it's our turn). */
    public volatile boolean boardWanted;

    private Session(OpenDraftHost host, String address, String asked, String helloSeed) {
        this.host = host;
        this.address = address;
        this.asked = asked;
        this.helloSeed = helloSeed;
        // The same lobby we were in when this game closed (or crashed) not long ago: ask for the name it gave us,
        // with the same secret, to get our place back. Our own lobby is always new.
        boolean rejoin = host == null && address.equals(Settings.rejoinAddress) && asked.equals(Settings.rejoinAsked)
                && Protocol.validToken(Settings.rejoinToken) && !Settings.rejoinName.isEmpty()
                && System.currentTimeMillis() - Settings.rejoinTime < (Protocol.HOLD_SECONDS + 60) * 1000L;
        this.token = rejoin ? Settings.rejoinToken : Protocol.newToken();
        this.me = rejoin ? Settings.rejoinName : asked;
    }

    public static Session current() {
        return current;
    }

    /**
     * Ends any current session and connects to a synchronizer in the background.
     * @param seed this world's seed, or "-" when joining from the title-screen lobby
     */
    public static Session connect(OpenDraftHost host, String address, String name, String seed) {
        end();
        Session session = new Session(host, address, Protocol.sanitizeName(name), seed);
        current = session;
        lastStatus = null;
        session.open(0);
        return session;
    }

    /**
     * Connects in the background, after {@code delay} ms. While reconnecting it keeps trying, more slowly each
     * time, for as long as the synchronizer keeps our place.
     */
    private void open(long delay) {
        Thread t = new Thread(() -> {
            long wait = Math.max(delay, 1000);
            if (delay > 0) pause(delay);
            while (current == this) {
                int n = attempt.incrementAndGet();
                try {
                    OpenDraftConnection c = OpenDraftConnection.connect(address, OpenDraftClient.modVersion(), helloSeed, me, token);
                    connection = c;
                    c.start(this::onLine, () -> lost(n));
                    if (current != this) c.close();
                    return;
                } catch (Exception e) {
                    if (!reconnecting) {
                        fail("Could not reach " + address + " (" + e.getMessage() + ").");
                        return;
                    }
                    if (System.currentTimeMillis() - lostAt >= Protocol.HOLD_SECONDS * 1000L) {
                        fail("Couldn't reconnect to the synchronizer (" + e.getMessage() + ").");
                        return;
                    }
                    OpenDraftClient.LOG.info("Reconnecting to the synchronizer failed: {}", e.getMessage());
                }
                pause(wait);
                wait = Math.min(wait * 2, 10_000);
            }
        }, "OpenDraft connect");
        t.setDaemon(true);
        t.start();
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Connection attempt {@code n} closed. Unless it was turned away or we're leaving, get back in. */
    private void lost(int n) {
        if (current != this || n != attempt.get()) return;
        if (lobbyClosed) {
            fail("The lobby was closed.");
            return;
        }
        if (rejected) {
            endIf(this); // REJECT already said why
            return;
        }
        if (!welcomed) {
            fail("Disconnected from the synchronizer.");
            return;
        }
        if (!reconnecting) {
            lostAt = System.currentTimeMillis();
            reconnecting = true;
            OpenDraftClient.chat(Component.literal("OpenDraft: lost the connection to the synchronizer. Reconnecting...")
                    .withStyle(ChatFormatting.YELLOW));
        } else if (System.currentTimeMillis() - lostAt >= Protocol.HOLD_SECONDS * 1000L) {
            fail("Couldn't reconnect to the synchronizer.");
            return;
        }
        open(1000);
    }

    private void fail(String why) {
        if (current != this) return;
        lastStatus = why;
        OpenDraftClient.chat(Component.literal("OpenDraft: " + why).withStyle(ChatFormatting.RED));
        endIf(this);
    }

    public static void end() {
        Session s = current;
        current = null;
        if (s != null) s.close();
    }

    /** Ends this session only if it is still the current one (used when its connection drops). */
    public static void endIf(Session s) {
        if (current == s) end();
    }

    private void close() {
        OpenDraftConnection c = connection;
        if (c != null) {
            // Leaving on purpose, so the synchronizer doesn't keep our place for a reconnect that won't come.
            if (c.isOpen() && welcomed && !reconnecting) c.send("LEAVE");
            c.close();
        }
        if (host != null) host.close();
        if (host == null && address.equals(Settings.rejoinAddress)) {
            Settings.rejoinTime = 0;
            Minecraft.getInstance().execute(Settings::save);
        }
    }

    /** Sends a line now. While disconnected, claims wait until we're back; anything else is dropped. */
    public void send(String line) {
        OpenDraftConnection c = connection;
        if (c != null && c.isOpen() && !reconnecting) c.send(line);
        else if (line.startsWith("CLAIM ")) outbox.add(line);
    }

    /** Remembers how to get our place back if the game closes, at most once a minute (and when welcomed). */
    private void rememberForRejoin(boolean now) {
        long t = System.currentTimeMillis();
        if (host != null || current != this || !now && t - rejoinSavedAt < 60_000) return;
        rejoinSavedAt = t;
        Minecraft.getInstance().execute(() -> {
            if (current != this) return; // left meanwhile; close() has forgotten it
            Settings.rejoinAddress = address;
            Settings.rejoinAsked = asked;
            Settings.rejoinName = me;
            Settings.rejoinToken = token;
            Settings.rejoinTime = t;
            Settings.save();
        });
    }

    /** The first player in join order runs the lobby; while they're away (dropped), the next one does. */
    public String leader() {
        List<String> p = players;
        Set<String> a = away;
        for (String name : p) if (!a.contains(name)) return name;
        return p.isEmpty() ? null : p.get(0);
    }

    public boolean isLeader() {
        return me != null && me.equals(leader());
    }

    /** A match is on: being set up, drafted or played. */
    public boolean matchOn() {
        return waitingForStart || drafting() || (goals != null && !ended);
    }

    /** The board is dealt and its clock is running (not in the lookout, not over). */
    public boolean running() {
        return goals != null && !ended && System.currentTimeMillis() >= startMillis;
    }

    /** Everyone is still looking around from the air; goals can't be claimed yet. */
    public boolean inLookout() {
        long start = startMillis;
        return goals != null && !ended && start != NOT_STARTED && System.currentTimeMillis() < start;
    }

    /** Whether a world with this seed is the one this match is played in (any world outside lobby matches). */
    public boolean rightWorld(long worldSeed) {
        Long seed = matchSeed;
        return seed == null || seed == worldSeed;
    }

    /** Whether the player in a world with this seed should be held in the air (loading, drafting or lookout). */
    public boolean holding(long worldSeed) {
        if (!rightWorld(worldSeed)) return false;
        return inLookout() || (waitingForStart && matchSeed != null);
    }

    /**
     * This game is in the match world but someone else's world is still loading. The view is covered
     * until then, so a faster computer doesn't get extra time to look around.
     */
    public boolean waitingForOthers(long worldSeed) {
        Long seed = matchSeed;
        return waitingForStart && !everyoneLoaded && seed != null && seed == worldSeed;
    }

    /** In the lookout's last {@link #GROUND_MILLIS}: the player is on the ground and can't go back up. */
    public boolean lookoutEnding() {
        return inLookout() && startMillis - System.currentTimeMillis() <= GROUND_MILLIS;
    }

    /** The leader has waited this long for the others' worlds, so they may start without them. */
    public static final long START_WITHOUT_MILLIS = 60_000;

    /** Others' worlds are still loading and the leader has waited long enough to start without them. */
    public boolean mayStartWithoutOthers() {
        return isLeader() && waitingForStart && !everyoneLoaded && matchSeed != null && readySent == matchId
                && System.currentTimeMillis() - readySentAt >= START_WITHOUT_MILLIS;
    }

    /** The leader's Start Without Them: the board is dealt now, and the others come in once their worlds are ready. */
    public void startWithoutOthers() {
        if (mayStartWithoutOthers()) send("STARTANYWAY " + matchId);
    }

    /** What the cover says while this game waits: who it's waiting for, or that its own terrain is loading. */
    public String waitingText() {
        if (readySent != matchId) return "Loading terrain...";
        List<String> others = new ArrayList<>(stillLoading);
        others.remove(me);
        if (others.isEmpty()) return "Waiting for other players...";
        String names = others.size() == 1 ? others.get(0)
                : String.join(", ", others.subList(0, others.size() - 1)) + " and " + others.get(others.size() - 1);
        return "Waiting for " + names + "...";
    }

    public boolean drafting() {
        return draftPool != null;
    }

    public boolean myTurn() {
        return drafting() && me != null && me.equals(turnPlayer);
    }

    /** What's left of the current draft turn, which doesn't run down while the draft is paused. */
    public long turnLeft() {
        return draftPaused ? pausedLeft : Math.max(0, turnDeadline - System.currentTimeMillis());
    }

    public boolean iForfeited() {
        return me != null && forfeited.contains(me);
    }

    public int score(String name) {
        int n = 0;
        for (String o : owners) if (name.equals(o)) n++;
        return n;
    }

    /** Everyone in this game: the lobby in join order, then anyone who left but still has claims. */
    public List<String> participants() {
        List<String> all = new ArrayList<>(players);
        for (String o : owners) if (o != null && !all.contains(o)) all.add(o);
        return all;
    }

    /** 1 for the most goals, 2 for the next and so on; tied players share a place. Forfeits don't count. */
    public int place(String name) {
        int mine = score(name), place = 1;
        for (String p : participants()) if (!forfeited.contains(p) && score(p) > mine) place++;
        return place;
    }

    /** Called on the network thread for each line from the synchronizer. */
    void onLine(String line) {
        String[] p = line.split(" ", 2);
        String rest = p.length > 1 ? p[1] : "";
        switch (p[0]) {
            case "PING" -> {
                send("PONG " + rest); // the synchronizer times this to judge close claims fairly
                rememberForRejoin(false);
            }
            case "WELCOME" -> {
                boolean back = welcomed;
                me = rest;
                welcomed = true;
                welcomes++;
                rememberForRejoin(true);
                if (!back) {
                    OpenDraftClient.chat(Component.literal("Joined the OpenDraft lobby as " + rest + ". Press "
                            + OpenDraftClient.boardKeyName() + " to see the board.").withStyle(ChatFormatting.GREEN));
                    return;
                }
                // Back after a dropped connection. What happened meanwhile follows (catching up changes nothing that's
                // already here). READY may have been lost with the connection; saying it twice is harmless.
                reconnecting = false;
                readySent = 0;
                // Claims sent just before the drop may never have arrived: goals still unowned are checked and sent again.
                String[] o = owners;
                sent.removeIf(i -> i >= o.length || o[i] == null);
                for (String claim : outbox) send(claim);
                outbox.clear();
                OpenDraftClient.chat(Component.literal("OpenDraft: reconnected.").withStyle(ChatFormatting.GREEN));
            }
            case "REJECT" -> {
                rejected = true;
                lastStatus = rest;
                OpenDraftClient.chat(Component.literal("OpenDraft: " + rest).withStyle(ChatFormatting.RED));
            }
            case "CLOSED" -> lobbyClosed = true;
            case "AWAY" -> away = rest.isBlank() ? Set.of() : Set.copyOf(Arrays.asList(rest.trim().split(" ")));
            case "PLAYERS" -> {
                // PLAYERS <name>:<colour>...
                List<String> names = new ArrayList<>();
                Map<String, Integer> c = new java.util.HashMap<>(colors);
                for (String entry : rest.isEmpty() ? new String[0] : rest.split(" ")) {
                    int colon = entry.lastIndexOf(':');
                    String name = colon < 0 ? entry : entry.substring(0, colon);
                    names.add(name);
                    if (colon >= 0) c.put(name, Integer.parseInt(entry.substring(colon + 1)));
                }
                colors = Map.copyOf(c);
                players = List.copyOf(names);
                // The leader's settings are what everyone sees; a new leader shares theirs.
                if (isLeader() && !Settings.decode(Settings.encode()).equals(lobbySettings)) send("SETTINGS " + Settings.encode());
            }
            case "SETTINGS" -> lobbySettings = Settings.decode(rest);
            case "WHERE" -> {
                // WHERE <name> <overworld|nether|end|lobby>
                String[] parts = rest.split(" ");
                if (parts.length != 2) return;
                Map<String, String> w = new java.util.HashMap<>(where);
                w.put(parts[0], parts[1]);
                where = Map.copyOf(w);
            }
            case "INFO" -> {
                addChatLine("* " + rest);
                OpenDraftClient.chat(Component.literal("OpenDraft: " + rest).withStyle(ChatFormatting.GRAY));
            }
            case "CHAT" -> {
                String[] parts = rest.split(" ", 2);
                if (parts.length < 2) return;
                addChatLine("<" + parts[0] + "> " + parts[1]);
                // The name in its board colour, the message in white. Player chat turned off keeps it to the lobby screen.
                if (!Settings.playerChat) return;
                OpenDraftClient.chat(Component.literal("<" + parts[0] + "> ").withColor(OpenDraftClient.colorOf(parts[0]) & 0xFFFFFF)
                        .append(Component.literal(parts[1]).withStyle(ChatFormatting.WHITE)));
            }
            case "MATCH" -> {
                // MATCH <matchId> <seed> <key>: a new world for everyone, then the board once all are in.
                String[] parts = rest.split(" ");
                int id = Integer.parseInt(parts[0]);
                long seed = Long.parseLong(parts[1]);
                // The match we're already in, sent again after a reconnect: nothing to reset or create.
                if (id == matchId) return;
                matchId = id;
                matchSeed = seed;
                matchKey = parts.length > 2 ? parts[2] : null;
                searchingBy = null;
                stillLoading = List.of();
                goals = null;
                owners = new String[0];
                draftPool = null;
                draftPaused = false;
                ended = false;
                winner = null;
                forfeited = Set.of();
                drawVotes = List.of();
                startMillis = NOT_STARTED;
                waitingForStart = true;
                everyoneLoaded = false;
                // This match's world if this game was in it before (a restart mid-match), otherwise a new one.
                WorldMaker.createMatchWorld(seed, matchKey);
            }
            case "GO" -> {
                // Everyone's world is loaded: the leader deals the board from their world.
                if (Integer.parseInt(rest.trim()) != matchId) return;
                everyoneLoaded = true;
                if (isLeader()) {
                    Minecraft mc = Minecraft.getInstance();
                    mc.execute(OpenDraftClient::dealBoard);
                }
            }
            case "DRAFTPOOL" -> {
                String[] parts = rest.split(" ");
                int id = Integer.parseInt(parts[0]);
                if (id == draftId && draftPool != null) return; // the same draft, sent again after a reconnect
                List<Goal> pool = resolve(Arrays.asList(parts).subList(1, parts.length));
                draftPicks = new String[pool.size()];
                turnPlayer = null;
                draftPaused = false;
                draftId = id;
                draftPool = pool;
                goals = null;
                owners = new String[0];
                ended = false;
                winner = null;
                boardWanted = true;
            }
            case "TURN" -> {
                String[] parts = rest.split(" ");
                turnPicks = Integer.parseInt(parts[1]);
                long left = Long.parseLong(parts[2]);
                turnDeadline = System.currentTimeMillis() + left;
                pausedLeft = left;
                boolean newTurn = !parts[0].equals(turnPlayer);
                turnPlayer = parts[0];
                if (newTurn && parts[0].equals(me)) {
                    OpenDraftClient.overlay(Component.literal("Your turn: pick " + turnPicks + (turnPicks == 1 ? " goal" : " goals"))
                            .withStyle(ChatFormatting.AQUA));
                    boardWanted = true;
                }
            }
            case "LOADING" -> stillLoading = rest.isBlank() ? List.of() : List.of(rest.trim().split(" "));
            case "SEARCHING" -> searchingBy = rest.equals("-") ? null : rest;
            case "DRAFTPAUSED", "DRAFTRESUMED" -> {
                if (Integer.parseInt(rest.trim()) == draftId) draftPaused = p[0].equals("DRAFTPAUSED");
            }
            case "PICKED" -> {
                String[] parts = rest.split(" ");
                int idx = Integer.parseInt(parts[0]);
                String[] picks = Arrays.copyOf(draftPicks, draftPicks.length);
                List<Goal> pool = draftPool;
                if (pool == null || idx < 0 || idx >= picks.length || parts[1].equals(picks[idx])) return; // known already
                picks[idx] = parts[1];
                draftPicks = picks; // shown on the board, not in chat
            }
            case "BOARD" -> {
                String[] parts = rest.split(" ");
                // The board we have, sent again after a reconnect. Resetting it would also restart goal tracking.
                if (Integer.parseInt(parts[0]) == boardId && goals != null) return;
                List<Goal> list = resolve(Arrays.asList(parts).subList(1, parts.length));
                sent.clear();
                owners = new String[list.size()];
                ended = false;
                winner = null;
                overtime = false;
                forfeited = Set.of();
                drawVotes = List.of();
                startMillis = NOT_STARTED;
                hadLookout = false;
                chatWiped = false;
                draftPool = null;
                boardId = Integer.parseInt(parts[0]);
                goals = list;
                BoardHud.visible = true; // hidden with the toggle key only until the next board
            }
            case "START" -> {
                // START <elapsedMillis> <limitSec> <overtimeSec>; elapsed is negative during the lookout.
                String[] parts = rest.split(" ");
                long elapsed = Long.parseLong(parts[0]);
                limitSec = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
                overtimeSec = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
                boolean again = startMillis != NOT_STARTED; // after a reconnect: only the clock is brought up to date
                if (!again) hadLookout = elapsed < 0;
                startMillis = System.currentTimeMillis() - elapsed;
                waitingForStart = false; // the lookout (if any) holds players from here
                if (again) return;
                // The goal count and time limit are on the board and the clock, so these only say what to do.
                if (elapsed < 0) {
                    OpenDraftClient.chat(Component.literal("Look around for " + (-elapsed + 999) / 1000 + "s: hold right-click"
                            + " to zoom, sneak to see from the ground, jump to go back up. You're put on the ground for the last "
                            + GROUND_MILLIS / 1000 + "s.")
                            .withStyle(ChatFormatting.GOLD));
                } else if (elapsed < 1000) {
                    // No lookout, so the clock starts now: clear the pre-match chat first.
                    chatWiped = true;
                    OpenDraftClient.clearChat();
                    OpenDraftClient.chat(Component.literal("The match has started!").withStyle(ChatFormatting.GOLD));
                }
            }
            case "OVERTIME" -> {
                if (overtime) return; // known already
                overtime = true;
                OpenDraftClient.chat(Component.literal("Time's up and it's a tie! Overtime: whoever pulls ahead first wins.")
                        .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            }
            case "CLAIMED" -> {
                String[] parts = rest.split(" ");
                int idx = Integer.parseInt(parts[0]);
                String[] o = Arrays.copyOf(owners, owners.length);
                if (idx < 0 || idx >= o.length || parts[1].equals(o[idx])) return; // known already
                o[idx] = parts[1];
                owners = o;
                Goal g = goals.get(idx);
                MutableComponent msg = Component.literal(parts[1].equals(me) ? "You" : parts[1])
                        .withColor(OpenDraftClient.colorOf(parts[1]) & 0xFFFFFF)
                        .append(Component.literal(" claimed ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(g == null ? "?" : g.name).withStyle(ChatFormatting.WHITE));
                OpenDraftClient.chat(msg);
            }
            case "FORFEITED" -> {
                String who = rest.trim();
                if (forfeited.contains(who)) return; // known already
                Set<String> f = new java.util.LinkedHashSet<>(forfeited);
                f.add(who);
                forfeited = f;
                OpenDraftClient.chat(Component.literal(who.equals(me) ? "You forfeited." : who + " forfeited.").withStyle(ChatFormatting.GRAY));
            }
            case "DRAWVOTE" -> {
                List<String> before = drawVotes;
                List<String> now = rest.isBlank() ? List.of() : List.of(rest.trim().split(" "));
                drawVotes = now;
                for (String v : now) {
                    if (before.contains(v) || v.equals(me)) continue;
                    OpenDraftClient.chat(Component.literal(v + " offers a draw. Open the board (" + OpenDraftClient.boardKeyName()
                            + ") to accept within " + Protocol.DRAW_OFFER_SECONDS + " seconds.").withStyle(ChatFormatting.YELLOW));
                }
                if (now.isEmpty() && !before.isEmpty() && !ended) {
                    OpenDraftClient.chat(Component.literal("The draw offer lapsed.").withStyle(ChatFormatting.GRAY));
                }
            }
            case "DRAWDECLINED" -> {
                drawVotes = List.of();
                OpenDraftClient.chat(Component.literal("The draw wasn't accepted within " + Protocol.DRAW_OFFER_SECONDS
                        + " seconds, so it's declined.").withStyle(ChatFormatting.YELLOW));
            }
            case "END" -> {
                if (ended && rest.equals(winner)) return; // known already
                ended = true;
                endMillis = System.currentTimeMillis();
                waitingForStart = false;
                winner = rest;
                OpenDraftClient.chat(Component.literal(rest.equals("-") ? "Game over: it's a draw!"
                        : rest.equals(me) ? "Game over: you win!" : "Game over: " + rest + " wins!").withStyle(ChatFormatting.GOLD));
                if (goals != null && participants().size() > 2) OpenDraftClient.chat(standings());
            }
            case "ADV" -> {
                // ADV <name> <task|goal|challenge> <title...>: shown like the vanilla advancement message.
                String[] parts = rest.split(" ", 3);
                if (parts.length < 3) return;
                String frame = switch (parts[1]) {
                    case "goal", "challenge" -> parts[1];
                    default -> "task";
                };
                // Grey apart from the player's board colour and the advancement's title, so it stands back from other chat.
                Component who = Component.literal(parts[0]).withColor(OpenDraftClient.colorOf(parts[0]) & 0xFFFFFF);
                Component title = Component.literal("[" + parts[2] + "]")
                        .withStyle(frame.equals("challenge") ? ChatFormatting.DARK_PURPLE : ChatFormatting.GREEN);
                OpenDraftClient.chat(Component.translatable("chat.type.advancement." + frame, who, title).withStyle(ChatFormatting.GRAY));
            }
            default -> {
            }
        }
    }

    /** "Final scores: Alex 9, You 6, Sam 4 (forfeit)", most goals first. */
    private Component standings() {
        List<String> order = new ArrayList<>(participants());
        order.sort((a, b) -> Integer.compare(score(b), score(a)));
        MutableComponent line = Component.literal("Final scores: ").withStyle(ChatFormatting.GRAY);
        for (int i = 0; i < order.size(); i++) {
            String p = order.get(i);
            if (i > 0) line.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
            line.append(Component.literal((p.equals(me) ? "You" : p) + " " + score(p) + (forfeited.contains(p) ? " (forfeit)" : ""))
                    .withColor(OpenDraftClient.colorOf(p) & 0xFFFFFF));
        }
        return line;
    }

    void addChatLine(String text) {
        chatLines.add(text);
        while (chatLines.size() > CHAT_LINES) chatLines.remove(0);
    }

    private static List<Goal> resolve(List<String> keys) {
        List<Goal> list = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String k : keys) {
            Goal g = Goals.get(k);
            if (g == null) unknown.add(k);
            list.add(g);
        }
        if (!unknown.isEmpty()) {
            OpenDraftClient.chat(Component.literal("OpenDraft: this copy of the mod doesn't know " + unknown
                    + ". Make sure everyone has the same mod version.").withStyle(ChatFormatting.RED));
        }
        return list;
    }
}
