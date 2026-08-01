package com.ka0s.pictopals;

/** Shared protocol constants. Newline-delimited JSON over TCP; JSON beacons over UDP broadcast. */
public final class Proto {
    public static final int TCP_PORT = 41117;
    public static final int UDP_PORT = 41118;
    public static final String MAGIC = "pictopals1";

    private Proto() {}
}
