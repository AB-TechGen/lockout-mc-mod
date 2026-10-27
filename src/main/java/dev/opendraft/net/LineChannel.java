package dev.opendraft.net;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * One TCP connection that sends and receives UTF-8 lines.
 * Lines are capped so a bad peer can't make us buffer forever.
 */
public final class LineChannel implements Lines, Closeable {
    public static final int MAX_LINE = 16 * 1024;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private volatile boolean closed;
    /** When the last line arrived (or the channel opened), to notice a connection that died without closing. */
    private volatile long lastRead = System.currentTimeMillis();

    public LineChannel(Socket socket) throws IOException {
        this.socket = socket;
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    @Override
    public String readLine() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        while (true) {
            int b = in.read();
            if (b < 0) {
                return buf.size() == 0 ? null : buf.toString(StandardCharsets.UTF_8);
            }
            if (b == '\n') {
                lastRead = System.currentTimeMillis();
                String line = buf.toString(StandardCharsets.UTF_8);
                return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            }
            if (buf.size() >= MAX_LINE) {
                throw new IOException("line too long");
            }
            buf.write(b);
        }
    }

    @Override
    public synchronized void send(String line) {
        if (closed) return;
        try {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            close();
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    public long lastRead() {
        return lastRead;
    }

    public String remote() {
        return String.valueOf(socket.getRemoteSocketAddress());
    }

    @Override
    public void close() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
