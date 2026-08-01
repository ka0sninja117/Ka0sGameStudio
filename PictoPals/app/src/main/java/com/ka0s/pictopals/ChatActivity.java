package com.ka0s.pictopals;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;

/**
 * The chat room. Message history on top; the compose area at the bottom has
 * three modes — text (default), drawing, and a D&D dice roller.
 */
public class ChatActivity extends Activity implements HostServer.Listener, ChatClient.Listener {

    private static final int[] DICE = {4, 6, 8, 10, 12, 20};
    /** Slightly above the host's 200-message history so replays always fit. */
    private static final int MAX_SHOWN_MESSAGES = 220;
    private static final int RECONNECT_DELAY_MS = 3000;

    private static class Msg {
        String name, color, sysText, text;
        int die, result;
        Bitmap bitmap;
    }

    private final List<Msg> messages = new ArrayList<>();
    private MsgAdapter adapter;
    private DrawingView drawView;
    private EditText msgEdit;
    private final Random random = new Random();

    private HostServer hostServer;
    private ChatClient client;
    private boolean isHost;

    // join-mode connection state, kept for auto-reconnect
    private String joinIp, myName, myColor;
    private int joinPort;
    private boolean everConnected = false;
    private boolean reconnecting = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private View textPane, drawPane, dicePane;
    private Button modeTextBtn, modeDrawBtn, modeDiceBtn;
    private final List<Button> diceButtons = new ArrayList<>();
    private TextView diceLabel;
    private int selectedDie = 20;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);
        applyEdgeToEdgeInsets();

        String mode = getIntent().getStringExtra("mode");
        String room = getIntent().getStringExtra("room");
        String name = getIntent().getStringExtra("name");
        String color = getIntent().getStringExtra("color");
        isHost = "host".equals(mode);

        TextView title = findViewById(R.id.roomTitle);
        title.setText(("?".equals(room) ? "Room" : "Room " + room)
                + (isHost ? "  (hosting)" : ""));

        drawView = findViewById(R.id.drawView);
        msgEdit = findViewById(R.id.msgEdit);
        ListView list = findViewById(R.id.msgList);
        adapter = new MsgAdapter();
        list.setAdapter(adapter);

        textPane = findViewById(R.id.textPane);
        drawPane = findViewById(R.id.drawPane);
        dicePane = findViewById(R.id.dicePane);
        modeTextBtn = findViewById(R.id.modeTextBtn);
        modeDrawBtn = findViewById(R.id.modeDrawBtn);
        modeDiceBtn = findViewById(R.id.modeDiceBtn);
        modeTextBtn.setOnClickListener(v -> showPane(textPane));
        modeDrawBtn.setOnClickListener(v -> showPane(drawPane));
        modeDiceBtn.setOnClickListener(v -> showPane(dicePane));
        showPane(textPane);

        // text mode
        findViewById(R.id.sendTextBtn).setOnClickListener(v -> sendTypedText());
        msgEdit.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTypedText();
                return true;
            }
            return false;
        });

        // draw mode
        Button penBtn = findViewById(R.id.penBtn);
        Button bigBtn = findViewById(R.id.bigBtn);
        Button eraseBtn = findViewById(R.id.eraseBtn);
        View[] toolBtns = {penBtn, bigBtn, eraseBtn};
        penBtn.setOnClickListener(v -> selectTool(DrawingView.TOOL_PEN, toolBtns, v));
        bigBtn.setOnClickListener(v -> selectTool(DrawingView.TOOL_BIG, toolBtns, v));
        eraseBtn.setOnClickListener(v -> selectTool(DrawingView.TOOL_ERASER, toolBtns, v));
        selectTool(DrawingView.TOOL_PEN, toolBtns, penBtn);
        findViewById(R.id.clearBtn).setOnClickListener(v -> drawView.clear());
        findViewById(R.id.sendDrawBtn).setOnClickListener(v -> sendDrawing());

        // dice mode
        buildDiceRow();
        findViewById(R.id.rollBtn).setOnClickListener(v -> rollAndSend());

        findViewById(R.id.leaveBtn).setOnClickListener(v -> finish());

        Button clearChatBtn = findViewById(R.id.clearChatBtn);
        if (isHost) {
            clearChatBtn.setVisibility(View.VISIBLE);
            clearChatBtn.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                    .setTitle("Clear chat")
                    .setMessage("Erase the chat for everyone in the room?")
                    .setPositiveButton("Clear", (d, w) -> hostServer.clearChat())
                    .setNegativeButton("Cancel", null)
                    .show());
        }

        // On Android 13+ the room-keepalive notification needs permission; the
        // service still protects the connection if the user declines.
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        if (isHost) {
            // Hosts keep the screen awake: many phones drop their hotspot when
            // the screen has been off a while, which would kill the whole room.
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            hostServer = new HostServer(room, name, color, this);
            try {
                hostServer.start();
                RoomService.start(this, "Hosting Room " + room);
                onSys("You are hosting Room " + room + ". Others on your hotspot/WiFi will see it appear.");
            } catch (IOException e) {
                Toast.makeText(this, "Couldn't host: is another room already hosted on this phone?",
                        Toast.LENGTH_LONG).show();
                finish();
            }
        } else {
            joinIp = getIntent().getStringExtra("ip");
            joinPort = getIntent().getIntExtra("port", Proto.TCP_PORT);
            myName = name;
            myColor = color;
            connectClient();
            RoomService.start(this, "?".equals(room) ? "In a room" : "In Room " + room);
            onSys("Joining…");
        }
    }

    private void connectClient() {
        client = new ChatClient(this);
        client.connect(joinIp, joinPort, myName, myColor);
    }

    /**
     * Android 15+ forces edge-to-edge. The dark header absorbs the status-bar
     * inset (so its background runs to the top edge of the screen), and the
     * root absorbs the side/bottom insets so the toolbar sits above the
     * navigation bar and the keyboard.
     */
    private void applyEdgeToEdgeInsets() {
        if (Build.VERSION.SDK_INT < 35) return;
        View root = findViewById(R.id.rootChat);
        View header = findViewById(R.id.chatHeader);
        root.setOnApplyWindowInsetsListener((v, wi) -> {
            android.graphics.Insets sb = wi.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            android.graphics.Insets ime = wi.getInsets(WindowInsets.Type.ime());
            v.setPadding(sb.left, 0, sb.right, Math.max(sb.bottom, ime.bottom));
            header.setPadding(header.getPaddingLeft(), sb.top,
                    header.getPaddingRight(), header.getPaddingBottom());
            return WindowInsets.CONSUMED;
        });
        // Dark header behind the status bar (light icons), light background
        // behind the navigation bar (dark icons).
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }
    }

    private void showPane(View pane) {
        textPane.setVisibility(pane == textPane ? View.VISIBLE : View.GONE);
        drawPane.setVisibility(pane == drawPane ? View.VISIBLE : View.GONE);
        dicePane.setVisibility(pane == dicePane ? View.VISIBLE : View.GONE);
        modeTextBtn.setAlpha(pane == textPane ? 1f : 0.45f);
        modeDrawBtn.setAlpha(pane == drawPane ? 1f : 0.45f);
        modeDiceBtn.setAlpha(pane == dicePane ? 1f : 0.45f);
    }

    private void selectTool(int tool, View[] toolBtns, View active) {
        drawView.setTool(tool);
        for (View b : toolBtns) b.setAlpha(b == active ? 1f : 0.45f);
    }

    private void buildDiceRow() {
        LinearLayout row = findViewById(R.id.diceRow);
        diceLabel = findViewById(R.id.diceLabel);
        for (int die : DICE) {
            Button b = new Button(this);
            b.setText("D" + die);
            b.setOnClickListener(v -> {
                selectedDie = die;
                diceLabel.setText("D" + die);
                for (Button db : diceButtons) db.setAlpha(db == b ? 1f : 0.45f);
            });
            diceButtons.add(b);
            row.addView(b, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        // default D20
        for (Button db : diceButtons) db.setAlpha("D20".contentEquals(db.getText()) ? 1f : 0.45f);
    }

    // ---- sending ----

    private void sendTypedText() {
        String text = msgEdit.getText().toString().trim();
        if (text.isEmpty()) return;
        try {
            JSONObject payload = new JSONObject();
            payload.put("text", text);
            sendPayload(payload);
            msgEdit.setText("");
        } catch (Exception ignored) {}
    }

    private void sendDrawing() {
        if (!drawView.hasContent()) {
            Toast.makeText(this, "Draw something first!", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("png", Base64.getEncoder().encodeToString(drawView.getPngBytes()));
            sendPayload(payload);
            drawView.clear();
        } catch (Exception ignored) {}
    }

    private void rollAndSend() {
        try {
            JSONObject payload = new JSONObject();
            payload.put("die", selectedDie);
            payload.put("result", random.nextInt(selectedDie) + 1);
            sendPayload(payload);
        } catch (Exception ignored) {}
    }

    private void sendPayload(JSONObject payload) {
        if (isHost) {
            hostServer.sendFromHost(payload);
        } else {
            client.send(payload);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        RoomService.stop(this);
        if (hostServer != null) hostServer.stop();
        if (client != null) client.close();
    }

    // ---- network callbacks (arrive on background threads) ----

    @Override
    public void onConnected() {
        runOnUiThread(() -> {
            everConnected = true;
            if (reconnecting) {
                reconnecting = false;
                // the host is about to replay the room history — start clean
                messages.clear();
                adapter.notifyDataSetChanged();
                addMsg(sysMsg("Reconnected!"));
            }
        });
    }

    @Override
    public void onMsg(JSONObject o) {
        Msg m = new Msg();
        m.name = o.optString("name", "?");
        m.color = o.optString("color", "#444444");
        if (o.has("png")) {
            m.bitmap = decodePngSafely(o.optString("png"));
            if (m.bitmap == null) return;
        } else if (o.has("die")) {
            m.die = o.optInt("die");
            m.result = o.optInt("result");
        } else if (o.has("text")) {
            m.text = o.optString("text");
        } else {
            return;
        }
        runOnUiThread(() -> addMsg(m));
    }

    /** Checks dimensions before allocating so a huge image can't balloon memory. */
    private static Bitmap decodePngSafely(String b64) {
        try {
            byte[] png = Base64.getDecoder().decode(b64);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(png, 0, png.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                    || bounds.outWidth > 2000 || bounds.outHeight > 2000) return null;
            return BitmapFactory.decodeByteArray(png, 0, png.length);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onSys(String text) {
        runOnUiThread(() -> addMsg(sysMsg(text)));
    }

    private static Msg sysMsg(String text) {
        Msg m = new Msg();
        m.sysText = text;
        return m;
    }

    /** Single point of insertion so the on-screen list (and its bitmaps) stays bounded. */
    private void addMsg(Msg m) {
        messages.add(m);
        while (messages.size() > MAX_SHOWN_MESSAGES) messages.remove(0);
        adapter.notifyDataSetChanged();
    }

    @Override
    public void onClear() {
        runOnUiThread(() -> {
            messages.clear();
            adapter.notifyDataSetChanged();
        });
    }

    @Override
    public void onClosed(String reason, boolean canRetry) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            // A drop before ever connecting means a wrong network, not a blip —
            // and canRetry=false means the host closed the room on purpose.
            if (!canRetry || !everConnected) {
                Toast.makeText(this, reason, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            if (!reconnecting) {
                reconnecting = true;
                addMsg(sysMsg("Connection lost — reconnecting… (LEAVE to give up)"));
            }
            handler.postDelayed(() -> {
                if (isFinishing() || isDestroyed()) return;
                client.close();
                connectClient();
            }, RECONNECT_DELAY_MS);
        });
    }

    // ---- message list ----

    private class MsgAdapter extends BaseAdapter {
        @Override
        public int getCount() { return messages.size(); }

        @Override
        public Object getItem(int position) { return messages.get(position); }

        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            // Views vary a lot (system vs message kinds); rebuild each time — counts stay small.
            Msg m = messages.get(position);
            Context ctx = ChatActivity.this;

            if (m.sysText != null) {
                TextView tv = new TextView(ctx);
                tv.setText(m.sysText);
                tv.setGravity(Gravity.CENTER_HORIZONTAL);
                tv.setTextColor(Color.parseColor("#7a8a99"));
                tv.setTextSize(12f);
                tv.setPadding(8, 10, 8, 10);
                return tv;
            }

            int frameColor;
            try {
                frameColor = Color.parseColor(m.color);
            } catch (Exception e) {
                frameColor = Color.DKGRAY;
            }

            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.WHITE);
            bg.setCornerRadius(14f);
            bg.setStroke(5, frameColor);
            box.setBackground(bg);
            LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            boxLp.setMargins(4, 6, 4, 6);
            box.setLayoutParams(boxLp);

            TextView nameTv = new TextView(ctx);
            nameTv.setText(m.name);
            nameTv.setTextColor(Color.WHITE);
            nameTv.setTextSize(12f);
            nameTv.setTypeface(null, Typeface.BOLD);
            nameTv.setPadding(16, 3, 16, 3);
            GradientDrawable nameBg = new GradientDrawable();
            nameBg.setColor(frameColor);
            nameBg.setCornerRadii(new float[]{10f, 10f, 0f, 0f, 10f, 0f, 0f, 0f});
            nameTv.setBackground(nameBg);
            box.addView(nameTv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            if (m.bitmap != null) {
                ImageView iv = new ImageView(ctx);
                iv.setImageBitmap(m.bitmap);
                iv.setAdjustViewBounds(true);
                iv.setScaleType(ImageView.ScaleType.FIT_XY);
                int w = parent.getWidth() > 0 ? parent.getWidth() - 40 : 800;
                int h = Math.round(w * (float) m.bitmap.getHeight() / m.bitmap.getWidth());
                box.addView(iv, new LinearLayout.LayoutParams(w, h));
            } else if (m.die > 0) {
                TextView tv = new TextView(ctx);
                tv.setText("🎲 rolled the D" + m.die + " …  " + m.result + " !");
                tv.setTextColor(Color.parseColor("#2b3f52"));
                tv.setTextSize(19f);
                tv.setTypeface(null, Typeface.BOLD);
                tv.setPadding(20, 14, 20, 16);
                box.addView(tv);
            } else {
                TextView tv = new TextView(ctx);
                tv.setText(m.text);
                tv.setTextColor(Color.BLACK);
                tv.setTextSize(17f);
                tv.setPadding(20, 12, 20, 14);
                box.addView(tv);
            }

            return box;
        }
    }
}
