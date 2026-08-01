package com.ka0s.pictopals;

import java.io.IOException;
import java.io.Reader;

/** Shared protocol constants. Newline-delimited JSON over TCP; JSON beacons over UDP broadcast. */
public final class Proto {
    public static final int TCP_PORT = 41117;
    public static final int UDP_PORT = 41118;
    public static final String MAGIC = "pictopals1";

    /** Longest accepted protocol line (chars). Biggest legit payload is a drawing PNG in base64. */
    public static final int MAX_LINE_CHARS = 600_000;

    /** Sockets are considered dead after this long without traffic; pings keep live ones under it. */
    public static final int READ_TIMEOUT_MS = 30_000;

    /**
     * Reads one newline-terminated line, refusing lines longer than max — a
     * plain BufferedReader.readLine() would buffer an arbitrarily long line
     * from a hostile/buggy peer until the app runs out of memory.
     */
    public static String readBoundedLine(Reader in, int max) throws IOException {
        StringBuilder sb = new StringBuilder(256);
        int ch;
        while ((ch = in.read()) != -1) {
            if (ch == '\n') return sb.toString();
            if (ch == '\r') continue;
            if (sb.length() >= max) throw new IOException("line too long");
            sb.append((char) ch);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private Proto() {}
}
