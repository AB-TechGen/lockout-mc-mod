package dev.opendraft;

import dev.opendraft.goal.Goal;
import dev.opendraft.goal.Goals;
import dev.opendraft.net.OpenDraftConnection;
import dev.opendraft.net.OpenDraftHost;
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

    /** Set right after connecting; lines can arrive before that, which is fine. */
    public volatile OpenDraftConnection connection;
    /** Non-null when this game is also running the synchronizer. */
    public final OpenDraftHost host;
    public final String address;

    public volatile String me;
    public volatile boolean welcomed;
    public volatile List<String> players = List.of();
    /** Everyone's colour number from the synchronizer, kept for players who left so their claims keep it. */
    public volatile Map<String, Integer> colors = Map.of();
    /** Where each player is ("overworld", "nether", "end" or "lobby"), for the tab list. */
    public volatile Map<String, String> where = Map.of();
    /** The leader's settings as the synchronizer last relayed them. */
    public volatile Map<String, String> lobbySettings = Map.of();
    public final List<String> chatLines = new CopyOnWriteArrayList<>();

    // The lobby match: everyone makes a world with matchSeed, and the board starts once all are in.
    public volatile int matchId;
    public volatile Long matchSeed;
    /** Between MATCH and the clock starting: players are held in the air while the others load. */
    public volatile boolean waitingForStart;
    public volatile int readySent;
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

    private Session(OpenDraftHost host, String address, String requestedName) {
        this.host = host;
        this.address = address;
        this.me = requestedName;
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
        Session session = new Session(host, address, dev.opendraft.net.Protocol.sanitizeName(name));
        current = session;
        lastStatus = null;
        Thread t = new Thread(() -> {
            try {
                OpenDraftConnection c = OpenDraftConnection.connect(address, OpenDraftClient.modVersion(), seed, session.me,
                        session::onLine, () -> {
                            if (current == session) {
                                lastStatus = "Disconnected from the synchronizer.";
                                OpenDraftClient.chat(Component.literal("OpenDraft: disconnected from the synchronizer.").withStyle(ChatFormatting.RED));
                                endIf(session);
                            }
                        });
                session.connection = c;
                if (current != session) c.close();
            } catch (Exception e) {
                lastStatus = "Could not reach " + address + " (" + e.getMessage() + ").";
                OpenDraftClient.chat(Component.literal("OpenDraft: " + lastStatus).withStyle(ChatFormatting.RED));
                endIf(session);
            }
        }, "OpenDraft connect");
        t.setDaemon(true);
        t.start();
        return session;
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
        if (c != null) c.close();
        if (host != null) host.close();
    }

    public void send(String line) {
        OpenDraftConnection c = connection;
        if (c != null) c.send(line);
    }

    /** The first player in join order runs the lobby. */
    public String leader() {
        List<String> p = players;
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

    public boolean drafting() {
        return draftPool != null;
    }

    public boolean myTurn() {
        return drafting() && me != null && me.equals(turnPlayer);
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
            case "PING" -> send("PONG " + rest); // the synchronizer times this to judge close claims fairly
            case "WELCOME" -> {
                me = rest;
                welcomed = true;
                OpenDraftClient.chat(Component.literal("Joined the OpenDraft lobby as " + rest + ". Press "
                        + OpenDraftClient.boardKeyName() + " to see the board.").withStyle(ChatFormatting.GREEN));
            }
            case "REJECT" -> {
                lastStatus = rest;
                OpenDraftClient.chat(Component.literal("OpenDraft: " + rest).withStyle(ChatFormatting.RED));
            }
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
                OpenDraftClient.chat(Component.literal("<" + parts[0] + "> ").withColor(OpenDraftClient.colorOf(parts[0]) & 0xFFFFFF)
                        .append(Component.literal(parts[1]).withStyle(ChatFormatting.WHITE)));
            }
            case "MATCH" -> {
                // MATCH <matchId> <seed>: a new world for everyone, then the board once all are in.
                String[] parts = rest.split(" ");
                int id = Integer.parseInt(parts[0]);
                long seed = Long.parseLong(parts[1]);
                boolean fresh = id != matchId;
                matchId = id;
                matchSeed = seed;
                goals = null;
                owners = new String[0];
                draftPool = null;
                ended = false;
                winner = null;
                forfeited = Set.of();
                drawVotes = List.of();
                startMillis = NOT_STARTED;
                waitingForStart = true;
                everyoneLoaded = false;
                if (fresh) WorldMaker.createMatchWorld(id, seed);
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
                List<Goal> pool = resolve(Arrays.asList(parts).subList(1, parts.length));
                draftPicks = new String[pool.size()];
                turnPlayer = null;
                draftId = Integer.parseInt(parts[0]);
                draftPool = pool;
                goals = null;
                owners = new String[0];
                ended = false;
                winner = null;
                openBoardScreen();
            }
            case "TURN" -> {
                String[] parts = rest.split(" ");
                turnPicks = Integer.parseInt(parts[1]);
                turnDeadline = System.currentTimeMillis() + Long.parseLong(parts[2]);
                boolean newTurn = !parts[0].equals(turnPlayer);
                turnPlayer = parts[0];
                if (newTurn && parts[0].equals(me)) {
                    OpenDraftClient.chat(Component.literal("Your turn: pick " + turnPicks + (turnPicks == 1 ? " goal" : " goals")
                            + " (" + Long.parseLong(parts[2]) / 1000 + "s). Press " + OpenDraftClient.boardKeyName()
                            + " if the board isn't open.").withStyle(ChatFormatting.AQUA));
                    openBoardScreen();
                }
            }
            case "PICKED" -> {
                String[] parts = rest.split(" ");
                int idx = Integer.parseInt(parts[0]);
                String[] picks = Arrays.copyOf(draftPicks, draftPicks.length);
                List<Goal> pool = draftPool;
                if (pool == null || idx < 0 || idx >= picks.length) return;
                picks[idx] = parts[1];
                draftPicks = picks;
                Goal g = pool.get(idx);
                OpenDraftClient.chat(Component.literal(parts[1].equals(me) ? "You" : parts[1])
                        .withColor(OpenDraftClient.colorOf(parts[1]) & 0xFFFFFF)
                        .append(Component.literal(" picked ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(g == null ? "?" : g.name).withStyle(ChatFormatting.WHITE)));
            }
            case "BOARD" -> {
                String[] parts = rest.split(" ");
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
                hadLookout = elapsed < 0;
                startMillis = System.currentTimeMillis() - elapsed;
                waitingForStart = false; // the lookout (if any) holds players from here
                List<Goal> g = goals;
                int n = g == null ? 0 : g.size();
                String time = Settings.timing(limitSec / 60, overtimeSec / 60).toLowerCase(java.util.Locale.ROOT);
                String key = OpenDraftClient.boardKeyName();
                if (elapsed < 0) {
                    OpenDraftClient.chat(Component.literal("OpenDraft: " + n + " goals, " + time + ". Look around for "
                            + (-elapsed + 999) / 1000 + "s first; hold right-click to zoom, sneak to see from the ground and jump "
                            + "to go back up. You're put on the ground for the last " + GROUND_MILLIS / 1000 + "s. Press " + key
                            + " for the board.")
                            .withStyle(ChatFormatting.GOLD));
                } else if (elapsed < 1000) {
                    OpenDraftClient.chat(Component.literal("The match has started! " + n + " goals, " + time + ". Press " + key + " for the board.")
                            .withStyle(ChatFormatting.GOLD));
                }
            }
            case "OVERTIME" -> {
                overtime = true;
                OpenDraftClient.chat(Component.literal("Time's up and it's a tie! Overtime: whoever pulls ahead first wins.")
                        .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            }
            case "CLAIMED" -> {
                String[] parts = rest.split(" ");
                int idx = Integer.parseInt(parts[0]);
                String[] o = Arrays.copyOf(owners, owners.length);
                if (idx < 0 || idx >= o.length) return;
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
                            + ") or use /opendraft draw to accept.").withStyle(ChatFormatting.YELLOW));
                }
                if (now.isEmpty() && !before.isEmpty() && !ended) {
                    OpenDraftClient.chat(Component.literal("The draw offer lapsed.").withStyle(ChatFormatting.GRAY));
                }
            }
            case "END" -> {
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

    private static void openBoardScreen() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null && mc.gui.screen() == null) mc.gui.setScreen(new BoardScreen());
        });
    }
}
