package dev.opendraft.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
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

    /** @param seed this world's seed, or "-" when joining from the title-screen lobby */
    public static OpenDraftConnection connect(String raw, String modVersion, String seed, String name,
                                            Consumer<String> onLine, Runnable onClosed) throws IOException {
        InetSocketAddress addr = parseAddress(raw);
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(addr.getHostString(), addr.getPort()), 8_000);
        LineChannel ch = new LineChannel(socket);
        OpenDraftConnection conn = new OpenDraftConnection(ch, addr.getHostString() + ":" + addr.getPort());
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
