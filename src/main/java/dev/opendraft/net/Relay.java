package dev.opendraft.net;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The OpenDraft relay: a small free program on Cloudflare (the {@code relay} folder of the
 * repository) that lets players join with a short lobby code when the host's router won't open a
 * port. The host's game keeps one connection to the relay, each joining player opens another, and
 * the relay passes lines between them. The synchronizer itself still runs in the host's game.
 */
public final class Relay {
    /**
     * The relays built into this copy of OpenDraft, separated by commas.
     * Lobbies open on the first one that answers; joining asks all of them for the code.
     */
    public static final String DEFAULT_URL = "https://opendraft-relay.mcopendraft.workers.dev";
    /** The relays in use: the built-in ones unless the settings name others. */
    public static volatile String url = DEFAULT_URL;

    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"; // no I, L, O or U
    private static final int CODE_LENGTH = 8;
    /** The header that proves a reconnecting host is the one the relay gave the code to. */
    private static final String KEY_HEADER = "X-OpenDraft-Key";
    /** The WebSocket close code the relay uses when a lobby closes for good, as opposed to its host reconnecting. */
    private static final int LOBBY_CLOSED = 4000;
    private static final ScheduledExecutorService PINGS = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "OpenDraft relay pings");
        t.setDaemon(true);
        return t;
    });

    private Relay() {
    }

    public static boolean available() {
        return !urls().isEmpty();
    }

    private static List<String> urls() {
        List<String> out = new ArrayList<>();
        String all = url;
        if (all == null) return out;
        for (String u : all.split(",")) {
            if (!u.isBlank()) out.add(base(u));
        }
        return out;
    }

    /** The lobby code without dashes or look-alike letters, or null if the text isn't one. */
    public static String normalize(String text) {
        if (text == null || text.contains(".") || text.contains(":")) return null;
        String code = text.trim().toUpperCase().replace("-", "").replace(" ", "")
                .replace('O', '0').replace('I', '1').replace('L', '1');
        if (code.length() != CODE_LENGTH) return null;
        for (char c : code.toCharArray()) if (ALPHABET.indexOf(c) < 0) return null;
        return code;
    }

    /** "K7QM2D9X" as "K7QM-2D9X". */
    public static String format(String code) {
        return code.substring(0, 4) + "-" + code.substring(4);
    }

    /**
     * Joins the lobby with this code (already normalized). The code doesn't say which relay it's on,
     * so every relay is asked at once and the one that has the lobby answers.
     */
    static Lines join(String code) throws IOException {
        List<String> relays = urls();
        if (relays.isEmpty()) throw new IOException("this copy of OpenDraft has no relay set up, so it can't use lobby codes");
        if (relays.size() == 1) return WebSocketLines.connect(relays.get(0) + "/join/" + code, Map.of());
        CompletableFuture<Lines> first = new CompletableFuture<>();
        AtomicInteger left = new AtomicInteger(relays.size());
        AtomicReference<IOException> problem = new AtomicReference<>();
        for (String relay : relays) {
            Thread t = new Thread(() -> {
                try {
                    WebSocketLines lines = WebSocketLines.connect(relay + "/join/" + code, Map.of());
                    if (!first.complete(lines)) lines.close();
                } catch (IOException e) {
                    // "No lobby" from the other relays is expected; any other problem is worth reporting.
                    if (!e.getMessage().startsWith("no lobby") || problem.get() == null) problem.set(e);
                    if (left.decrementAndGet() == 0) first.completeExceptionally(problem.get());
                }
            }, "OpenDraft relay join");
            t.setDaemon(true);
            t.start();
        }
        try {
            return first.get();
        } catch (ExecutionException e) {
            throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    /** What the host's game hears about its connection to the relay. Called on the relay's thread. */
    public interface Watcher {
        /** The connection dropped, and the game is getting the same code back. */
        void reconnecting();

        /** It's back, with the same code. */
        void back();

        /** It's gone for good: nobody can join with the code anymore. */
        void lost();
    }

    /**
     * Opens a lobby on the relay for the synchronizer listening on {@code localPort}. A relay that knows about
     * reconnecting (asked for with ?v=2) also hands out a key, which gets the same code back after a drop; an older
     * relay ignores the question and only gives the code.
     */
    public static Bridge host(int localPort, Consumer<String> log, Watcher watcher) throws IOException {
        List<String> relays = urls();
        if (relays.isEmpty()) throw new IOException("no relay is set up");
        IOException problem = null;
        for (String address : relays) {
            try {
                WebSocketLines relay = WebSocketLines.connect(address + "/host?v=2", Map.of());
                String first = relay.readLine(8_000);
                // ROOM <code> [<key>]
                String[] room = first != null && first.startsWith("ROOM ") ? first.substring(5).trim().split(" ") : new String[0];
                String code = room.length > 0 ? normalize(room[0]) : null;
                if (code == null) {
                    relay.close();
                    throw new IOException("the relay didn't hand out a lobby code");
                }
                String key = room.length > 1 && !room[1].isBlank() ? room[1] : null;
                Bridge bridge = new Bridge(relay, address, localPort, code, key, log, watcher);
                Thread t = new Thread(bridge::run, "OpenDraft relay");
                t.setDaemon(true);
                t.start();
                return bridge;
            } catch (IOException e) {
                log.accept("relay " + address + " didn't work: " + e.getMessage());
                problem = e;
            }
        }
        throw problem;
    }

    private static String base(String address) {
        String u = address.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.startsWith("https://")) return "wss://" + u.substring(8);
        if (u.startsWith("http://")) return "ws://" + u.substring(7);
        return u.contains("://") ? u : "wss://" + u;
    }

    /**
     * The host's side. For each player who joins through the relay it opens an ordinary local
     * connection to the synchronizer, so the synchronizer can't tell them from anyone else.
     */
    public static final class Bridge {
        /** The connection to the relay; replaced when it drops and the code is got back. */
        private volatile WebSocketLines relay;
        private final String address;
        private final int localPort;
        private final String code;
        /** Gets the code back after a drop; null from a relay that doesn't do that. */
        private final String key;
        private final Consumer<String> log;
        private final Watcher watcher;
        private final Map<String, LineChannel> players = new ConcurrentHashMap<>();
        private volatile boolean stopped;

        private Bridge(WebSocketLines relay, String address, int localPort, String code, String key, Consumer<String> log, Watcher watcher) {
            this.relay = relay;
            this.address = address;
            this.localPort = localPort;
            this.code = code;
            this.key = key;
            this.log = log;
            this.watcher = watcher;
        }

        /** The lobby code, formatted for showing. */
        public String code() {
            return format(code);
        }

        public void close() {
            WebSocketLines r;
            synchronized (this) {
                stopped = true;
                r = relay;
            }
            r.send("BYE"); // the relay forgets the code now instead of keeping it for a reconnect; older relays ignore it
            r.close();
            for (LineChannel c : players.values()) c.close();
            players.clear();
        }

        private void run() {
            while (true) {
                WebSocketLines r = relay;
                try {
                    for (String line; (line = r.readLine()) != null; ) handle(r, line);
                } catch (IOException ignored) {
                }
                // Everyone who came through the relay is cut off with it. The synchronizer keeps their places, and
                // their games reconnect by themselves once the code is back.
                for (LineChannel c : players.values()) c.close();
                players.clear();
                if (stopped) return;
                if (key == null || !reconnect()) {
                    log.accept("lost the connection to the relay");
                    watcher.lost();
                    return;
                }
            }
        }

        /** Gets the same code back with the key, for as long as the relay keeps it after a drop. */
        private boolean reconnect() {
            log.accept("lost the connection to the relay, getting the code back");
            watcher.reconnecting();
            long giveUp = System.currentTimeMillis() + Protocol.HOLD_SECONDS * 1000L;
            long wait = 1000;
            while (!stopped && System.currentTimeMillis() < giveUp) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    return false;
                }
                wait = Math.min(wait * 2, 15_000);
                try {
                    WebSocketLines r = WebSocketLines.connect(address + "/host/" + code, Map.of(KEY_HEADER, key));
                    String first = r.readLine(8_000);
                    if (first == null || !first.startsWith("ROOM ")) {
                        r.close();
                        continue;
                    }
                    synchronized (this) {
                        if (stopped) {
                            r.close();
                            return false;
                        }
                        relay = r;
                    }
                    log.accept("back on the relay with code " + format(code));
                    watcher.back();
                    return true;
                } catch (Refused e) {
                    log.accept("the relay won't give the code back: " + e.getMessage());
                    return false;
                } catch (IOException e) {
                    log.accept("still can't reach the relay: " + e.getMessage());
                }
            }
            return false;
        }

        /** A message from the relay connection {@code r}. Each player's lines go back over the connection they came on. */
        private void handle(WebSocketLines r, String message) {
            String[] p = message.split(" ", 3);
            String id = p.length > 1 ? p[1] : "";
            switch (p[0]) {
                case "OPEN" -> open(r, id);
                case "DATA" -> {
                    LineChannel c = players.get(id);
                    if (c != null) c.send(p.length > 2 ? p[2] : "");
                }
                case "CLOSE" -> {
                    LineChannel c = players.remove(id);
                    if (c != null) c.close();
                }
                default -> {
                }
            }
        }

        private void open(WebSocketLines r, String id) {
            LineChannel c;
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), localPort), 3_000);
                c = new LineChannel(s);
            } catch (IOException e) {
                r.send("CLOSE " + id);
                return;
            }
            players.put(id, c);
            Thread t = new Thread(() -> {
                try {
                    for (String line; (line = c.readLine()) != null; ) r.send("DATA " + id + " " + line);
                } catch (IOException ignored) {
                }
                c.close();
                if (players.remove(id) != null) r.send("CLOSE " + id);
            }, "OpenDraft relay player");
            t.setDaemon(true);
            t.start();
        }
    }

    /** The relay turned the connection down (no such lobby, full, or a wrong key), so trying again won't help. */
    static final class Refused extends IOException {
        Refused(String message) {
            super(message);
        }
    }

    /** Lines over a WebSocket: each message is one line. */
    static final class WebSocketLines implements Lines, WebSocket.Listener {
        private static final Object END = new Object();
        /**
         * Hearing nothing for this long, not even the relay's answer to our pings, means the connection died
         * without closing (a network that drops it silently); it's closed then, so it counts as dropped.
         */
        private static final long SILENT_MS = 25_000;

        private final LinkedBlockingQueue<Object> incoming = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();
        private volatile WebSocket ws;
        private volatile boolean closed;
        private volatile long lastHeard = System.currentTimeMillis();
        private CompletableFuture<?> sending = CompletableFuture.completedFuture(null);
        private ScheduledFuture<?> ping;

        static WebSocketLines connect(String uri, Map<String, String> headers) throws IOException {
            WebSocketLines lines = new WebSocketLines();
            try {
                WebSocket.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build().newWebSocketBuilder();
                headers.forEach(b::header);
                lines.ws = b.buildAsync(URI.create(uri), lines).get(12, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                String why = describe(e.getCause());
                throw e.getCause() instanceof WebSocketHandshakeException h && h.getResponse().statusCode() / 100 == 4
                        && h.getResponse().statusCode() != 409 ? new Refused(why) : new IOException(why);
            } catch (TimeoutException e) {
                throw new IOException("the relay didn't answer");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            } catch (IllegalArgumentException e) {
                throw new IOException("the relay address isn't valid");
            }
            // Keeps the connection from looking idle while nobody claims anything, and shows when it's died.
            lines.lastHeard = System.currentTimeMillis();
            lines.ping = PINGS.scheduleAtFixedRate(lines::ping, 10, 10, TimeUnit.SECONDS);
            return lines;
        }

        private static String describe(Throwable t) {
            if (t instanceof WebSocketHandshakeException h) {
                return switch (h.getResponse().statusCode()) {
                    case 404 -> "no lobby has that code (it may have closed)";
                    case 403 -> "that lobby is full, or the code belongs to someone else";
                    case 409 -> "the relay still has the old connection";
                    default -> "the relay said no (HTTP " + h.getResponse().statusCode() + ")";
                };
            }
            if (t instanceof ConnectException || t instanceof HttpTimeoutException) return "couldn't reach the relay";
            return t == null ? "unknown error" : String.valueOf(t.getMessage());
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            lastHeard = System.currentTimeMillis();
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            lastHeard = System.currentTimeMillis(); // the reply is sent for us
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            lastHeard = System.currentTimeMillis();
            partial.append(data);
            if (partial.length() > LineChannel.MAX_LINE + 64) { // room for "DATA <id> "
                close();
                return null;
            }
            if (last) {
                incoming.add(partial.toString());
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            // The relay closing the lobby for good reads as the synchronizer's own CLOSED, so the game doesn't try to
            // reconnect. Any other close (such as the host reconnecting) is just a dropped connection.
            if (statusCode == LOBBY_CLOSED) incoming.add("CLOSED");
            ended();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            ended();
        }

        @Override
        public String readLine() throws IOException {
            try {
                return next(incoming.take());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
        }

        /** Like {@link #readLine()}, but gives up with null after {@code timeoutMs}. */
        String readLine(long timeoutMs) throws IOException {
            try {
                Object o = incoming.poll(timeoutMs, TimeUnit.MILLISECONDS);
                return o == null ? null : next(o);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
        }

        private String next(Object o) {
            if (o == END) {
                incoming.add(END); // later reads see the end too
                return null;
            }
            return (String) o;
        }

        @Override
        public synchronized void send(String line) {
            if (closed) return;
            // The WebSocket takes one message at a time, so each send waits for the one before. Lines queued before
            // close() still go out (a goodbye, say); close() sends the close after them.
            sending = sending.handle((r, e) -> null)
                    .thenCompose(x -> ws.isOutputClosed() ? CompletableFuture.completedFuture(null) : ws.sendText(line, true))
                    .whenComplete((r, e) -> {
                        if (e != null) close();
                    });
        }

        private synchronized void ping() {
            if (closed) return;
            if (System.currentTimeMillis() - lastHeard > SILENT_MS) {
                close();
                return;
            }
            sending = sending.handle((r, e) -> null)
                    .thenCompose(x -> closed ? CompletableFuture.completedFuture(null) : ws.sendPing(ByteBuffer.allocate(0)));
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            CompletableFuture<?> queued;
            synchronized (this) {
                if (closed) return;
                ended();
                queued = sending;
            }
            WebSocket w = ws;
            if (w == null) return;
            try {
                queued.handle((r, e) -> null)
                        .thenCompose(x -> w.sendClose(WebSocket.NORMAL_CLOSURE, ""))
                        .orTimeout(3, TimeUnit.SECONDS)
                        .whenComplete((r, e) -> w.abort());
            } catch (RuntimeException e) {
                w.abort();
            }
        }

        private void ended() {
            closed = true;
            if (ping != null) ping.cancel(false);
            incoming.add(END);
        }
    }
}
