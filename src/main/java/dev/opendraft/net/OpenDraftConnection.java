package dev.opendraft.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** This game's connection to the synchronizer. Incoming lines go to {@code onLine} on a background thread. */
public final class OpenDraftConnection {
    private final LineChannel channel;
    private final String address;

    private OpenDraftConnection(LineChannel channel, String address) {
        this.channel = channel;
        this.address = address;
    }

    /** Parses "host", "host:port" or "[v6]:port". */
    public static InetSocketAddress parseAddress(String raw) {
        String s = raw.trim();
        int port = Protocol.DEFAULT_PORT;
        String host = s;
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            host = s.substring(1, end);
            if (end + 1 < s.length() && s.charAt(end + 1) == ':') port = Integer.parseInt(s.substring(end + 2));
        } else if (s.indexOf(':') >= 0 && s.indexOf(':') == s.lastIndexOf(':')) {
            host = s.substring(0, s.indexOf(':'));
            port = Integer.parseInt(s.substring(s.indexOf(':') + 1));
        }
        return InetSocketAddress.createUnresolved(host, port);
    }

    /**
     * @param raw an invite code or an address
     * @param seed this world's seed, or "-" when joining from the title-screen lobby
     */
    public static OpenDraftConnection connect(String raw, String modVersion, String seed, String name,
                                            Consumer<String> onLine, Runnable onClosed) throws IOException {
        List<InetSocketAddress> targets = Invite.decode(raw);
        Socket socket = open(targets != null ? targets : List.of(parseAddress(raw)), 8_000);
        LineChannel ch = new LineChannel(socket);
        OpenDraftConnection conn = new OpenDraftConnection(ch, socket.getInetAddress().getHostAddress() + ":" + socket.getPort());
        ch.send("HELLO " + Protocol.VERSION + " " + modVersion + " " + seed + " " + Protocol.sanitizeName(name));
        Thread reader = new Thread(() -> {
            try {
                for (String line; (line = ch.readLine()) != null; ) onLine.accept(line);
            } catch (IOException ignored) {
            } finally {
                ch.close();
                onClosed.run();
            }
        }, "OpenDraft connection");
        reader.setDaemon(true);
        reader.start();
        return conn;
    }

    /**
     * Connects to whichever address answers first. An invite code holds the host's home-network
     * and internet addresses, and only one of them works from any given place.
     */
    static Socket open(List<InetSocketAddress> targets, int timeoutMs) throws IOException {
        if (targets.size() == 1) {
            InetSocketAddress t = targets.get(0);
            Socket s = new Socket();
            s.connect(new InetSocketAddress(t.getHostString(), t.getPort()), timeoutMs);
            return s;
        }
        CompletableFuture<Socket> first = new CompletableFuture<>();
        AtomicInteger left = new AtomicInteger(targets.size());
        for (InetSocketAddress t : targets) {
            Thread th = new Thread(() -> {
                Socket s = new Socket();
                try {
                    s.connect(new InetSocketAddress(t.getHostString(), t.getPort()), timeoutMs);
                    if (!first.complete(s)) s.close();
                } catch (IOException e) {
                    try {
                        s.close();
                    } catch (IOException ignored) {
                    }
                    if (left.decrementAndGet() == 0) first.completeExceptionally(e);
                }
            }, "OpenDraft connect");
            th.setDaemon(true);
            th.start();
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

    public void send(String line) {
        channel.send(line);
    }

    public boolean isOpen() {
        return !channel.isClosed();
    }

    public String address() {
        return address;
    }

    public void close() {
        channel.close();
    }
}
