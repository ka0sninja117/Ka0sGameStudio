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
 */
public class HostServer {

    /** Message payload keys relayed verbatim from sender to everyone. */
    private static final String[] MSG_KEYS = {"png", "text", "die", "result"};

    private static final int HISTORY_LIMIT = 200;

    public interface Listener {
        /** msg carries name, color and one of: png (base64) / text / die+result. */
        void onMsg(JSONObject msg);
        void onSys(String text);
        void onClear();
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
    }

    public void stop() {
        running = false;
        worker.shutdown();
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        if (beaconSocket != null) beaconSocket.close();
        for (Client c : clients) {
            try { c.sock.close(); } catch (IOException ignored) {}
        }
        clients.clear();
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
                s.setTcpNoDelay(true);
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
            while (running && (line = in.readLine()) != null) {
                JSONObject o = new JSONObject(line);
                String t = o.optString("t");
                if ("join".equals(t)) {
                    c.name = o.optString("name", "?");
                    c.color = o.optString("color", "#444444");
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
                } else if ("msg".equals(t)) {
                    relay(c.name, c.color, o);
                }
            }
        } catch (Exception ignored) {
        } finally {
            clients.remove(c);
            try { c.sock.close(); } catch (IOException ignored) {}
            if (c.joined && running) {
                broadcastSys(c.name + " left the room");
            }
        }
    }

    private void relay(String name, String color, JSONObject payload) {
        worker.execute(() -> {
            try {
                JSONObject o = new JSONObject();
                o.put("t", "msg");
                o.put("name", name);
                o.put("color", color);
                boolean hasContent = false;
                for (String k : MSG_KEYS) {
                    if (payload.has(k)) {
                        o.put(k, payload.get(k));
                        hasContent = true;
                    }
                }
                if (!hasContent) return;
                broadcastLine(o.toString());
                listener.onMsg(o);
            } catch (Exception ignored) {}
        });
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
