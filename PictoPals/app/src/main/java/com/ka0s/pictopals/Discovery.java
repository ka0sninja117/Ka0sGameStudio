package com.ka0s.pictopals;

import android.content.Context;
import android.net.wifi.WifiManager;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Listens for host beacons on the local network so the lobby can show
 * "Room A — Dad (3 chatting)" without anyone typing an IP address.
 */
public class Discovery {

    public interface Listener {
        void onRoomSeen(String room, String host, String ip, int port, int users);
    }

    private final Listener listener;
    private DatagramSocket socket;
    private volatile boolean running = true;
    private WifiManager.MulticastLock lock;

    public Discovery(Listener listener) {
        this.listener = listener;
    }

    public void start(Context context) {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                lock = wm.createMulticastLock("pictopals");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
        } catch (Exception ignored) {}

        new Thread(() -> {
            try {
                socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(Proto.UDP_PORT));
                byte[] buf = new byte[2048];
                while (running) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    socket.receive(p);
                    try {
                        String s = new String(p.getData(), p.getOffset(), p.getLength(),
                                StandardCharsets.UTF_8);
                        JSONObject o = new JSONObject(s);
                        if (!Proto.MAGIC.equals(o.optString("m"))) continue;
                        listener.onRoomSeen(
                                o.optString("room", "?"),
                                o.optString("host", "?"),
                                p.getAddress().getHostAddress(),
                                o.optInt("port", Proto.TCP_PORT),
                                o.optInt("users", 1));
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {
            }
        }, "pp-discovery").start();
    }

    public void stop() {
        running = false;
        if (socket != null) socket.close();
        try { if (lock != null && lock.isHeld()) lock.release(); } catch (Exception ignored) {}
    }
}
