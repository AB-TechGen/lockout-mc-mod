package dev.opendraft.net;

import java.io.IOException;

/** A connection that carries text lines: a direct TCP connection, or one through the relay. */
public interface Lines {
    /** Returns the next line, or null once the other side has closed the connection. */
    String readLine() throws IOException;

    void send(String line);

    boolean isClosed();

    void close();
}
