package com.ka0s.pictopals;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs on the phone that hosts a room. Accepts TCP clients, relays every message
 * to all of them, and broadcasts a UDP discovery beacon once per second so other
 * phones on the same hotspot/WiFi can find the room without typing an IP.
 *
 * The host also keeps the room's chat history in memory and replays it to every
 * newcomer, so late joiners see the whole conversation. History lives exactly as
 * long as the room does: when the host leaves, the room closes and it is gone.
 *
 * Everything received from the network is treated as untrusted: lines are
 * length-bounded, names/colors/text/dice/drawings are validated and capped, and
 * connections that go silent past the read timeout are dropped (live clients
 * send heartbeat pings well inside it).
 */
public class HostServer {

    private static final int HISTORY_LIMIT = 200;
    private static final int MAX_CLIENTS = 15;
    private static final int MAX_PNG_B64_CHARS = 400_000;
    private static final int MAX_TEXT_CHARS = 500;
    private static final int MAX_NAME_CHARS = 16;
    private static final int MAX_ROLLS = 10;
    /** Every Nth 1s beacon, ping all clients so their read timeout never trips while idle. */
    private static final int PING_EVERY_N_BEACONS = 10;

    public interface Listener {
        /** msg carries name, color and one of: png (base64) / text / die+results. */
        void onMsg(JSONObject msg);
        void onSys(String text);
        void onClear();
        /** Current room roster: array of {name, color}, host first. */
        void onUsers(org.json.JSONArray users);
    }

    private final String room;
    private final String hostName;
    private final String hostColor;
    private final Listener listener;

    private ServerSocket server;
    private DatagramSocket beaconSocket;
    private volatile boolean running = true;
    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private final List<String> history = new ArrayList<>();
    /**
     * All broadcasting funnels through this worker: it keeps message order
     * consistent and, crucially, keeps socket writes off the UI thread when
     * the host itself sends (Android forbids network I/O on the main thread).
     */
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private static class Client {
        final Socket sock;
        final BufferedWriter out;
        volatile String name = "?";
        volatile String color = "#444444";
        volatile boolean joined = false;

        Client(Socket s) throws IOException {
            sock = s;
            out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
        }

        synchronized void send(String line) {
            try {
                out.write(line);
                out.write('\n');
                out.flush();
            } catch (IOException ignored) {
                // reader thread will notice the dead socket and clean up
            }
        }
    }

    public HostServer(String room, String hostName, String hostColor, Listener listener) {
        this.room = room;
        this.hostName = hostName;
        this.hostColor = hostColor;
        this.listener = listener;
    }

    /** Throws if the port is already taken (e.g. another room hosted on this phone). */
    public void start() throws IOException {
        server = new ServerSocket(Proto.TCP_PORT);
        beaconSocket = new DatagramSocket();
        beaconSocket.setBroadcast(true);
        new Thread(this::acceptLoop, "pp-accept").start();
        new Thread(this::beaconLoop, "pp-beacon").start();
        broadcastUsers(); // seed the host's own member list
    }

    /** Says goodbye to clients (so they don't try to reconnect), then shuts down. */
    public void stop() {
        running = false;
        worker.execute(() -> {
            try {
                JSONObject o = new JSONObject();
                o.put("t", "bye");
                String line = o.toString();
                for (Client c : clients) if (c.joined) c.send(line);
            } catch (Exception ignored) {}
            try { if (server != null) server.close(); } catch (IOException ignored) {}
            if (beaconSocket != null) beaconSocket.close();
            for (Client c : clients) {
                try { c.sock.close(); } catch (IOException ignored) {}
            }
            clients.clear();
        });
        worker.shutdown();
    }

    /** Called from the host's own UI. payload holds png / text / die+result. */
    public void sendFromHost(JSONObject payload) {
        relay(hostName, hostColor, payload);
    }

    /** Host-only: wipes the room's history and everyone's screens. */
    public void clearChat() {
        worker.execute(() -> {
            synchronized (history) {
                history.clear();
            }
            try {
                JSONObject o = new JSONObject();
                o.put("t", "clear");
                String line = o.toString();
                for (Client c : clients) if (c.joined) c.send(line);
                listener.onClear();
            } catch (Exception ignored) {}
        });
        broadcastSys(hostName + " cleared the chat");
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                if (clients.size() >= MAX_CLIENTS) {
                    try { s.close(); } catch (IOException ignored) {}
                    continue;
                }
                s.setTcpNoDelay(true);
                s.setSoTimeout(Proto.READ_TIMEOUT_MS);
                Client c = new Client(s);
                clients.add(c);
                new Thread(() -> readLoop(c), "pp-client").start();
            } catch (IOException e) {
                break; // server socket closed
            }
        }
    }

    private void readLoop(Client c) {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(c.sock.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = Proto.readBoundedLine(in, Proto.MAX_LINE_CHARS)) != null) {
                JSONObject o = new JSONObject(line);
                String t = o.optString("t");
                if ("join".equals(t)) {
                    c.name = sanitizeName(o.optString("name"));
                    c.color = sanitizeColor(o.optString("color"));
                    // On the worker so the history replay can't interleave
                    // with a message being broadcast at the same moment.
                    worker.execute(() -> {
                        try {
                            JSONObject welcome = new JSONObject();
                            welcome.put("t", "welcome");
                            welcome.put("room", room);
                            c.send(welcome.toString());
                            synchronized (history) {
                                for (String h : history) c.send(h);
                            }
                            c.joined = true;
                        } catch (Exception ignored) {}
                    });
                    broadcastSys(c.name + " joined the room");
                    broadcastUsers();
                } else if ("msg".equals(t)) {
                    relay(c.name, c.color, o);
                }
                // "ping" (and anything unknown) needs no handling: any traffic
                // resets the read timeout, which is the ping's whole job
            }
        } catch (Exception ignored) {
            // covers disconnects, read timeouts on silent sockets, and bad JSON
        } finally {
            clients.remove(c);
            try { c.sock.close(); } catch (IOException ignored) {}
            if (c.joined && running) {
                broadcastSys(c.name + " left the room");
                broadcastUsers();
            }
        }
    }

    /**
     * Sends the current roster to everyone. Deliberately not stored in
     * history — it's current state, and each newcomer triggers a fresh
     * broadcast right after their join anyway.
     */
    private void broadcastUsers() {
        worker.execute(() -> {
            try {
                org.json.JSONArray list = new org.json.JSONArray();
                JSONObject me = new JSONObject();
                me.put("name", hostName);
                me.put("color", hostColor);
                list.put(me);
                for (Client c : clients) {
                    if (!c.joined) continue;
                    JSONObject u = new JSONObject();
                    u.put("name", c.name);
                    u.put("color", c.color);
                    list.put(u);
                }
                JSONObject o = new JSONObject();
                o.put("t", "users");
                o.put("list", list);
                String line = o.toString();
                for (Client c : clients) if (c.joined) c.send(line);
                listener.onUsers(list);
            } catch (Exception ignored) {}
        });
    }

    private void relay(String name, String color, JSONObject payload) {
        worker.execute(() -> {
            try {
                JSONObject o = new JSONObject();
                o.put("t", "msg");
                o.put("name", name);
                o.put("color", color);
                // Exactly one message kind is accepted, validated and capped.
                if (payload.has("png")) {
                    String png = payload.optString("png");
                    if (png.isEmpty() || png.length() > MAX_PNG_B64_CHARS) return;
                    o.put("png", png);
                } else if (payload.has("die")) {
                    int die = payload.optInt("die");
                    org.json.JSONArray results = payload.optJSONArray("results");
                    if (!isRealDie(die) || results == null) return;
                    int n = results.length();
                    if (n < 1 || n > MAX_ROLLS) return;
                    for (int i = 0; i < n; i++) {
                        int r = results.optInt(i);
                        if (r < 1 || r > die) return;
                    }
                    o.put("die", die);
                    o.put("results", results);
                } else if (payload.has("text")) {
                    String text = payload.optString("text").trim();
                    if (text.isEmpty()) return;
                    if (text.length() > MAX_TEXT_CHARS) text = text.substring(0, MAX_TEXT_CHARS);
                    o.put("text", text);
                } else {
                    return;
                }
                broadcastLine(o.toString());
                listener.onMsg(o);
            } catch (Exception ignored) {}
        });
    }

    private static boolean isRealDie(int die) {
        switch (die) {
            case 4: case 6: case 8: case 10: case 12: case 20:
                return true;
            default:
                return false;
        }
    }

    private static String sanitizeName(String raw) {
        StringBuilder sb = new StringBuilder();
        for (char ch : raw.trim().toCharArray()) {
            if (!Character.isISOControl(ch)) sb.append(ch);
            if (sb.length() >= MAX_NAME_CHARS) break;
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }

    private static String sanitizeColor(String raw) {
        return raw.matches("#[0-9a-fA-F]{6}") ? raw : "#444444";
    }

    private void broadcastSys(String text) {
        worker.execute(() -> {
            try {
                JSONObject o = new JSONObject();
                o.put("t", "sys");
                o.put("text", text);
                broadcastLine(o.toString());
                listener.onSys(text);
            } catch (Exception ignored) {}
        });
    }

    private void broadcastLine(String line) {
        synchronized (history) {
            history.add(line);
            if (history.size() > HISTORY_LIMIT) history.remove(0);
        }
        for (Client c : clients) if (c.joined) c.send(line);
    }

    private void beaconLoop() {
        int tick = 0;
        while (running) {
            try {
                JSONObject o = new JSONObject();
                o.put("m", Proto.MAGIC);
                o.put("t", "beacon");
                o.put("room", room);
                o.put("host", hostName);
                o.put("port", Proto.TCP_PORT);
                o.put("users", clients.size() + 1);
                byte[] data = o.toString().getBytes(StandardCharsets.UTF_8);
                for (InetAddress addr : broadcastAddresses()) {
                    try {
                        beaconSocket.send(new DatagramPacket(data, data.length, addr, Proto.UDP_PORT));
                    } catch (IOException ignored) {}
                }
                if (++tick % PING_EVERY_N_BEACONS == 0) {
                    worker.execute(() -> {
                        try {
                            JSONObject ping = new JSONObject();
                            ping.put("t", "ping");
                            String line = ping.toString();
                            for (Client c : clients) if (c.joined) c.send(line);
                        } catch (Exception ignored) {}
                    });
                }
                Thread.sleep(1000);
            } catch (Exception e) {
                if (!running) break;
            }
        }
    }

    private static List<InetAddress> broadcastAddresses() {
        List<InetAddress> out = new ArrayList<>();
        try {
            out.add(InetAddress.getByName("255.255.255.255"));
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b != null && !out.contains(b)) out.add(b);
                }
            }
        } catch (Exception ignored) {}
        return out;
    }
}
