package com.ka0s.pictopals;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Lobby: pick a name + color, then host a room or join one discovered nearby. */
public class MainActivity extends Activity {

    private static final String[] COLORS = {
            "#3b6ad4", "#d43b3b", "#2f9e44", "#e8890c",
            "#8a3bd4", "#d43b98", "#12a5a5", "#444444"
    };
    private static final String[] ROOMS = {"A", "B", "C", "D"};

    private SharedPreferences prefs;
    private EditText nameEdit;
    private String selectedColor = COLORS[0];
    private LinearLayout colorRow, roomList;
    private TextView noRooms;

    private Discovery discovery;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private static class Room {
        String room, host, ip;
        int port, users;
        long lastSeen;
        View row;
    }

    private final Map<String, Room> rooms = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("pictopals", MODE_PRIVATE);
        nameEdit = findViewById(R.id.nameEdit);
        colorRow = findViewById(R.id.colorRow);
        roomList = findViewById(R.id.roomList);
        noRooms = findViewById(R.id.noRooms);

        nameEdit.setText(prefs.getString("name", ""));
        selectedColor = prefs.getString("color", COLORS[0]);

        buildColorRow();
        buildHostRow();

        findViewById(R.id.joinIpBtn).setOnClickListener(v -> promptJoinByIp());
    }

    @Override
    protected void onResume() {
        super.onResume();
        rooms.clear();
        roomList.removeAllViews();
        noRooms.setVisibility(View.VISIBLE);
        discovery = new Discovery(this::onRoomSeen);
        discovery.start(this);
        handler.postDelayed(pruneTask, 2000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (discovery != null) discovery.stop();
        handler.removeCallbacks(pruneTask);
    }

    private final Runnable pruneTask = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<String, Room>> it = rooms.entrySet().iterator();
            while (it.hasNext()) {
                Room r = it.next().getValue();
                if (now - r.lastSeen > 4000) {
                    roomList.removeView(r.row);
                    it.remove();
                }
            }
            noRooms.setVisibility(rooms.isEmpty() ? View.VISIBLE : View.GONE);
            handler.postDelayed(this, 2000);
        }
    };

    private void onRoomSeen(String room, String host, String ip, int port, int users) {
        runOnUiThread(() -> {
            String key = ip + "/" + room;
            Room r = rooms.get(key);
            if (r == null) {
                r = new Room();
                r.room = room;
                r.ip = ip;
                Button b = new Button(this);
                b.setAllCaps(false);
                b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                final Room fr = r;
                b.setOnClickListener(v -> join(fr.ip, fr.port, fr.room));
                r.row = b;
                rooms.put(key, r);
                roomList.addView(b, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            r.host = host;
            r.port = port;
            r.users = users;
            r.lastSeen = System.currentTimeMillis();
            ((Button) r.row).setText("Room " + room + "  —  " + host
                    + "  (" + users + " chatting)");
            noRooms.setVisibility(View.GONE);
        });
    }

    private void buildColorRow() {
        for (String c : COLORS) {
            View swatch = new View(this);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor(c));
            bg.setCornerRadius(8f);
            if (c.equals(selectedColor)) bg.setStroke(6, Color.parseColor("#2b3f52"));
            swatch.setBackground(bg);
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            lp.setMargins(4, 0, 4, 0);
            swatch.setOnClickListener(v -> {
                selectedColor = c;
                colorRow.removeAllViews();
                buildColorRow();
            });
            colorRow.addView(swatch, lp);
        }
    }

    private void buildHostRow() {
        LinearLayout hostRow = findViewById(R.id.hostRow);
        for (String room : ROOMS) {
            Button b = new Button(this);
            b.setText(room);
            b.setOnClickListener(v -> host(room));
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            hostRow.addView(b, lp);
        }
    }

    private String requireName() {
        String name = nameEdit.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "Enter your name first!", Toast.LENGTH_SHORT).show();
            return null;
        }
        prefs.edit().putString("name", name).putString("color", selectedColor).apply();
        return name;
    }

    private void host(String room) {
        String name = requireName();
        if (name == null) return;
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra("mode", "host");
        i.putExtra("room", room);
        i.putExtra("name", name);
        i.putExtra("color", selectedColor);
        startActivity(i);
    }

    private void join(String ip, int port, String room) {
        String name = requireName();
        if (name == null) return;
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra("mode", "join");
        i.putExtra("room", room);
        i.putExtra("ip", ip);
        i.putExtra("port", port);
        i.putExtra("name", name);
        i.putExtra("color", selectedColor);
        startActivity(i);
    }

    private void promptJoinByIp() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("e.g. 192.168.43.1");
        new AlertDialog.Builder(this)
                .setTitle("Join by IP address")
                .setMessage("Ask the host to open Settings → About phone → IP address (or Hotspot settings), then type it here.")
                .setView(input)
                .setPositiveButton("Join", (d, w) -> {
                    String ip = input.getText().toString().trim();
                    if (!ip.isEmpty()) join(ip, Proto.TCP_PORT, "?");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
