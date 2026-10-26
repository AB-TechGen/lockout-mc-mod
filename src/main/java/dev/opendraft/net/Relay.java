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
        if (relays.size() == 1) return WebSocketLines.connect(relays.get(0) + "/join/" + code);
        CompletableFuture<Lines> first = new CompletableFuture<>();
        AtomicInteger left = new AtomicInteger(relays.size());
        AtomicReference<IOException> problem = new AtomicReference<>();
        for (String relay : relays) {
            Thread t = new Thread(() -> {
                try {
                    WebSocketLines lines = WebSocketLines.connect(relay + "/join/" + code);
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

    /**
     * Opens a lobby on the relay for the synchronizer listening on {@code localPort}.
     * @param onLost runs if the connection to the relay drops before {@link Bridge#close}
     */
    public static Bridge host(int localPort, Consumer<String> log, Runnable onLost) throws IOException {
        List<String> relays = urls();
        if (relays.isEmpty()) throw new IOException("no relay is set up");
        IOException problem = null;
        for (String address : relays) {
            try {
                WebSocketLines relay = WebSocketLines.connect(address + "/host");
                String first = relay.readLine(8_000);
                String code = first != null && first.startsWith("ROOM ") ? normalize(first.substring(5)) : null;
                if (code == null) {
                    relay.close();
                    throw new IOException("the relay didn't hand out a lobby code");
                }
                Bridge bridge = new Bridge(relay, localPort, code, log);
                Thread t = new Thread(() -> bridge.run(onLost), "OpenDraft relay");
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
        private final WebSocketLines relay;
        private final int localPort;
        private final String code;
        private final Consumer<String> log;
        private final Map<String, LineChannel> players = new ConcurrentHashMap<>();
        private volatile boolean stopped;

        private Bridge(WebSocketLines relay, int localPort, String code, Consumer<String> log) {
            this.relay = relay;
            this.localPort = localPort;
            this.code = code;
            this.log = log;
        }

        /** The lobby code, formatted for showing. */
        public String code() {
            return format(code);
        }

        public void close() {
            stopped = true;
            relay.close();
            for (LineChannel c : players.values()) c.close();
            players.clear();
        }

        private void run(Runnable onLost) {
            try {
                for (String line; (line = relay.readLine()) != null; ) handle(line);
            } catch (IOException ignored) {
            }
            for (LineChannel c : players.values()) c.close();
            players.clear();
            if (!stopped) {
                log.accept("lost the connection to the relay");
                onLost.run();
            }
        }

        private void handle(String message) {
            String[] p = message.split(" ", 3);
            String id = p.length > 1 ? p[1] : "";
            switch (p[0]) {
                case "OPEN" -> open(id);
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

        private void open(String id) {
            LineChannel c;
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), localPort), 3_000);
                c = new LineChannel(s);
            } catch (IOException e) {
                relay.send("CLOSE " + id);
                return;
            }
            players.put(id, c);
            Thread t = new Thread(() -> {
                try {
                    for (String line; (line = c.readLine()) != null; ) relay.send("DATA " + id + " " + line);
                } catch (IOException ignored) {
                }
                c.close();
                if (players.remove(id) != null) relay.send("CLOSE " + id);
            }, "OpenDraft relay player");
            t.setDaemon(true);
            t.start();
        }
    }

    /** Lines over a WebSocket: each message is one line. */
    static final class WebSocketLines implements Lines, WebSocket.Listener {
        private static final Object END = new Object();

        private final LinkedBlockingQueue<Object> incoming = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();
        private volatile WebSocket ws;
        private volatile boolean closed;
        private CompletableFuture<?> sending = CompletableFuture.completedFuture(null);
        private ScheduledFuture<?> ping;

        static WebSocketLines connect(String uri) throws IOException {
            WebSocketLines lines = new WebSocketLines();
            try {
                lines.ws = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()
                        .newWebSocketBuilder().buildAsync(URI.create(uri), lines).get(12, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                throw new IOException(describe(e.getCause()));
            } catch (TimeoutException e) {
                throw new IOException("the relay didn't answer");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            } catch (IllegalArgumentException e) {
                throw new IOException("the relay address isn't valid");
            }
            // Keeps the connection from looking idle while nobody claims anything.
            lines.ping = PINGS.scheduleAtFixedRate(lines::ping, 30, 30, TimeUnit.SECONDS);
            return lines;
        }

        private static String describe(Throwable t) {
            if (t instanceof WebSocketHandshakeException h) {
                return switch (h.getResponse().statusCode()) {
                    case 404 -> "no lobby has that code (it may have closed)";
                    case 403 -> "that lobby is full";
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
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
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
            // The WebSocket takes one message at a time, so each send waits for the one before.
            sending = sending.handle((r, e) -> null)
                    .thenCompose(x -> closed ? CompletableFuture.completedFuture(null) : ws.sendText(line, true))
                    .whenComplete((r, e) -> {
                        if (e != null) close();
                    });
        }

        private synchronized void ping() {
            if (closed) return;
            sending = sending.handle((r, e) -> null)
                    .thenCompose(x -> closed ? CompletableFuture.completedFuture(null) : ws.sendPing(ByteBuffer.allocate(0)));
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            if (closed) return;
            ended();
            WebSocket w = ws;
            if (w == null) return;
            try {
                w.sendClose(WebSocket.NORMAL_CLOSURE, "").orTimeout(3, TimeUnit.SECONDS).whenComplete((r, e) -> w.abort());
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
