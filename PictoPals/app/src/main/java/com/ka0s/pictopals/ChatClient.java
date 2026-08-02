package com.ka0s.pictopals;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * TCP client used by phones that join a hosted room. Sends a heartbeat ping
 * every few seconds so the host knows the connection is alive; reads carry a
 * timeout so a dead host is detected within ~30s. Reconnecting is the
 * activity's job — it creates a fresh ChatClient when onClosed says it can.
 */
public class ChatClient {

    private static final int PING_INTERVAL_S = 10;

    public interface Listener {
        /** The room accepted us; history replay (if any) follows as onMsg calls. */
        void onConnected();
        /** msg carries name, color and one of: png (base64) / text / die+result. */
        void onMsg(JSONObject msg);
        void onSys(String text);
        void onClear();
        /** Current room roster: array of {name, color}, host first. */
        void onUsers(org.json.JSONArray users);
        /** canRetry=false means the host deliberately closed the room. */
        void onClosed(String reason, boolean canRetry);
    }

    private final Listener listener;
    private Socket socket;
    private BufferedWriter out;
    private volatile boolean running = true;
    private final ScheduledExecutorService sender = Executors.newSingleThreadScheduledExecutor();

    public ChatClient(Listener listener) {
        this.listener = listener;
    }

    /** Connects and joins in a background thread; results arrive via the listener. */
    public void connect(String ip, int port, String name, String color) {
        new Thread(() -> {
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(ip, port), 5000);
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(Proto.READ_TIMEOUT_MS);
                out = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                JSONObject join = new JSONObject();
                join.put("t", "join");
                join.put("name", name);
                join.put("color", color);
                writeLine(join.toString());

                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while (running && (line = Proto.readBoundedLine(in, Proto.MAX_LINE_CHARS)) != null) {
                    JSONObject o = new JSONObject(line);
                    String t = o.optString("t");
                    if ("msg".equals(t)) {
                        listener.onMsg(o);
                    } else if ("sys".equals(t)) {
                        listener.onSys(o.optString("text"));
                    } else if ("clear".equals(t)) {
                        listener.onClear();
                    } else if ("users".equals(t)) {
                        org.json.JSONArray list = o.optJSONArray("list");
                        if (list != null) listener.onUsers(list);
                    } else if ("welcome".equals(t)) {
                        startPings();
                        listener.onConnected();
                    } else if ("bye".equals(t)) {
                        running = false;
                        listener.onClosed("The host closed the room.", false);
                        return;
                    }
                    // unknown types are ignored for forward compatibility
                }
                if (running) {
                    running = false;
                    listener.onClosed("Connection to the room was lost.", true);
                }
            } catch (Exception e) {
                if (running) {
                    running = false;
                    listener.onClosed("Couldn't reach the room. Are you on the host's hotspot/WiFi?", true);
                }
            } finally {
                try { if (socket != null) socket.close(); } catch (IOException ignored) {}
            }
        }, "pp-clientread").start();
    }

    private void startPings() {
        sender.scheduleWithFixedDelay(() -> {
            try {
                JSONObject o = new JSONObject();
                o.put("t", "ping");
                writeLine(o.toString());
            } catch (Exception ignored) {
                // reader thread notices the dead socket and reports onClosed
            }
        }, PING_INTERVAL_S, PING_INTERVAL_S, TimeUnit.SECONDS);
    }

    /** payload holds png / text / die+result; the host adds name and color. */
    public void send(JSONObject payload) {
        sender.execute(() -> {
            try {
                payload.put("t", "msg");
                writeLine(payload.toString());
            } catch (Exception ignored) {}
        });
    }

    private synchronized void writeLine(String line) throws IOException {
        out.write(line);
        out.write('\n');
        out.flush();
    }

    public void close() {
        running = false;
        sender.shutdownNow();
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }
}
