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
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** TCP client used by phones that join a hosted room. */
public class ChatClient {

    public interface Listener {
        void onMsg(String name, String color, byte[] png);
        void onSys(String text);
        void onClosed(String reason);
    }

    private final Listener listener;
    private Socket socket;
    private BufferedWriter out;
    private volatile boolean running = true;
    private final ExecutorService sender = Executors.newSingleThreadExecutor();

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
                while (running && (line = in.readLine()) != null) {
                    JSONObject o = new JSONObject(line);
                    String t = o.optString("t");
                    if ("msg".equals(t)) {
                        listener.onMsg(o.optString("name"), o.optString("color", "#444444"),
                                Base64.getDecoder().decode(o.optString("png")));
                    } else if ("sys".equals(t)) {
                        listener.onSys(o.optString("text"));
                    }
                    // "welcome" needs no handling beyond a successful connection
                }
                if (running) listener.onClosed("The room was closed by the host.");
            } catch (Exception e) {
                if (running) listener.onClosed("Couldn't reach the room. Are you on the host's hotspot/WiFi?");
            }
        }, "pp-clientread").start();
    }

    public void send(byte[] png) {
        sender.execute(() -> {
            try {
                JSONObject o = new JSONObject();
                o.put("t", "msg");
                o.put("png", Base64.getEncoder().encodeToString(png));
                writeLine(o.toString());
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
        sender.shutdown();
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }
}
