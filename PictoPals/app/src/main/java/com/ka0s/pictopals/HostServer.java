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
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runs on the phone that hosts a room. Accepts TCP clients, relays every message
 * to all of them, and broadcasts a UDP discovery beacon once per second so other
 * phones on the same hotspot/WiFi can find the room without typing an IP.
 */
public class HostServer {

    public interface Listener {
        void onMsg(String name, String color, byte[] png);
        void onSys(String text);
    }

    private final String room;
    private final String hostName;
    private final String hostColor;
    private final Listener listener;

    private ServerSocket server;
    private DatagramSocket beaconSocket;
    private volatile boolean running = true;
    private final List<Client> clients = new CopyOnWriteArrayList<>();

    private static class Client {
        final Socket sock;
        final BufferedWriter out;
        volatile String name = "?";
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
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        if (beaconSocket != null) beaconSocket.close();
        for (Client c : clients) {
            try { c.sock.close(); } catch (IOException ignored) {}
        }
        clients.clear();
    }

    /** Called from the host's own UI when the host sends a drawing. */
    public void sendFromHost(byte[] png) {
        broadcastMsg(hostName, hostColor, png);
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
                    String color = o.optString("color", "#444444");
                    c.joined = true;
                    JSONObject welcome = new JSONObject();
                    welcome.put("t", "welcome");
                    welcome.put("room", room);
                    c.send(welcome.toString());
                    broadcastSys(c.name + " joined the room");
                    // remember the client's color for its messages
                    clientColor(c, color);
                } else if ("msg".equals(t)) {
                    byte[] png = Base64.getDecoder().decode(o.optString("png"));
                    broadcastMsg(c.name, colorOf(c), png);
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

    // Tiny per-client color storage without another field class
    private final java.util.Map<Client, String> colors = new java.util.concurrent.ConcurrentHashMap<>();
    private void clientColor(Client c, String color) { colors.put(c, color); }
    private String colorOf(Client c) { return colors.getOrDefault(c, "#444444"); }

    private void broadcastMsg(String name, String color, byte[] png) {
        try {
            JSONObject o = new JSONObject();
            o.put("t", "msg");
            o.put("name", name);
            o.put("color", color);
            o.put("png", Base64.getEncoder().encodeToString(png));
            String line = o.toString();
            for (Client c : clients) if (c.joined) c.send(line);
            listener.onMsg(name, color, png);
        } catch (Exception ignored) {}
    }

    private void broadcastSys(String text) {
        try {
            JSONObject o = new JSONObject();
            o.put("t", "sys");
            o.put("text", text);
            String line = o.toString();
            for (Client c : clients) if (c.joined) c.send(line);
            listener.onSys(text);
        } catch (Exception ignored) {}
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
