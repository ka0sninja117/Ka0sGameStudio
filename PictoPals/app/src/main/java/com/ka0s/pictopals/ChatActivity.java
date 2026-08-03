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
        int die;
        int[] results;
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

    private View textPane, drawPane, dicePane, gamePane;
    private Button modeTextBtn, modeDrawBtn, modeDiceBtn, modeGameBtn;
    private LinearLayout gameContent;

    // Chicken Time Warp client-side state
    private JSONObject gameState;
    private final List<String> myHand = new ArrayList<>();
    private android.app.AlertDialog reactDialog;
    /** Last banner sequence rendered, so a new event animates exactly once. */
    private int lastSeqSeen = -1;
    private boolean animateBanner = false;
    private boolean wasMyTurn = false;
    private int gameBtnDefaultColor;
    private final List<Button> diceButtons = new ArrayList<>();
    private TextView diceLabel;
    private int selectedDie = 20;
    private int rollCount = 1;
    private static final int MAX_ROLL_COUNT = 10;

    /** Latest roster from the host: [name, color] pairs, host first. */
    private final List<String[]> roomUsers = new ArrayList<>();

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
        myName = name;
        myColor = color;

        TextView title = findViewById(R.id.roomTitle);
        title.setText(("?".equals(room) ? "Room" : "Room " + room)
                + (isHost ? " (hosting)" : "") + "  👥");
        title.setOnClickListener(v -> showMembersDialog());

        drawView = findViewById(R.id.drawView);
        msgEdit = findViewById(R.id.msgEdit);
        ListView list = findViewById(R.id.msgList);
        adapter = new MsgAdapter();
        list.setAdapter(adapter);

        textPane = findViewById(R.id.textPane);
        drawPane = findViewById(R.id.drawPane);
        dicePane = findViewById(R.id.dicePane);
        gamePane = findViewById(R.id.gamePane);
        gameContent = findViewById(R.id.gameContent);
        modeTextBtn = findViewById(R.id.modeTextBtn);
        modeDrawBtn = findViewById(R.id.modeDrawBtn);
        modeDiceBtn = findViewById(R.id.modeDiceBtn);
        modeGameBtn = findViewById(R.id.modeGameBtn);
        gameBtnDefaultColor = modeGameBtn.getCurrentTextColor();
        modeTextBtn.setOnClickListener(v -> showPane(textPane));
        modeDrawBtn.setOnClickListener(v -> showPane(drawPane));
        modeDiceBtn.setOnClickListener(v -> showPane(dicePane));
        modeGameBtn.setOnClickListener(v -> {
            showPane(gamePane);
            renderGame();
        });
        showPane(textPane);
        renderGame();

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
        findViewById(R.id.undoBtn).setOnClickListener(v -> drawView.undo());
        findViewById(R.id.clearBtn).setOnClickListener(v -> drawView.clear());
        findViewById(R.id.sendDrawBtn).setOnClickListener(v -> sendDrawing());
        try {
            drawView.setPenColor(Color.parseColor(color));
        } catch (Exception ignored) {}

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
        gamePane.setVisibility(pane == gamePane ? View.VISIBLE : View.GONE);
        modeTextBtn.setAlpha(pane == textPane ? 1f : 0.45f);
        modeDrawBtn.setAlpha(pane == drawPane ? 1f : 0.45f);
        modeDiceBtn.setAlpha(pane == dicePane ? 1f : 0.45f);
        modeGameBtn.setAlpha(pane == gamePane ? 1f : 0.45f);
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
            // Tapping the selected die again stacks the roll (2× D20, 3× D20…),
            // wrapping back to 1× past the cap.
            b.setOnClickListener(v -> {
                if (selectedDie == die) {
                    rollCount = rollCount % MAX_ROLL_COUNT + 1;
                } else {
                    selectedDie = die;
                    rollCount = 1;
                }
                diceLabel.setText((rollCount > 1 ? rollCount + "× " : "") + "D" + selectedDie);
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
            org.json.JSONArray results = new org.json.JSONArray();
            for (int i = 0; i < rollCount; i++) {
                results.put(random.nextInt(selectedDie) + 1);
            }
            JSONObject payload = new JSONObject();
            payload.put("die", selectedDie);
            payload.put("results", results);
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
            org.json.JSONArray arr = o.optJSONArray("results");
            if (arr == null || arr.length() == 0) return;
            m.results = new int[arr.length()];
            for (int i = 0; i < arr.length(); i++) m.results[i] = arr.optInt(i);
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
    public void onUsers(org.json.JSONArray users) {
        List<String[]> parsed = new ArrayList<>();
        for (int i = 0; i < users.length(); i++) {
            JSONObject u = users.optJSONObject(i);
            if (u != null) parsed.add(new String[]{u.optString("name", "?"),
                    u.optString("color", "#444444")});
        }
        runOnUiThread(() -> {
            roomUsers.clear();
            roomUsers.addAll(parsed);
        });
    }

    private void showMembersDialog() {
        if (roomUsers.isEmpty()) {
            Toast.makeText(this, "Member list hasn't arrived yet — try again in a second.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence[] items = new CharSequence[roomUsers.size()];
        for (int i = 0; i < roomUsers.size(); i++) {
            String[] u = roomUsers.get(i);
            String label = "●  " + u[0] + (i == 0 ? "  (host)" : "");
            android.text.SpannableString s = new android.text.SpannableString(label);
            int c;
            try {
                c = Color.parseColor(u[1]);
            } catch (Exception e) {
                c = Color.DKGRAY;
            }
            s.setSpan(new android.text.style.ForegroundColorSpan(c), 0, 1, 0);
            items[i] = s;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("In this room (" + roomUsers.size() + ")")
                .setItems(items, null)
                .setPositiveButton("Close", null)
                .show();
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

    // ---- Chicken Time Warp ----

    @Override
    public void onGame(JSONObject o) {
        runOnUiThread(() -> {
            String e = o.optString("e");
            if ("state".equals(e)) {
                gameState = o;
                int seq = o.optInt("seq", 0);
                animateBanner = lastSeqSeen >= 0 && seq != lastSeqSeen;
                lastSeqSeen = seq;
                if (reactDialog != null && !myName.equals(o.optString("waiting", ""))) {
                    reactDialog.dismiss();
                    reactDialog = null;
                }
                renderGame();
            } else if ("hand".equals(e)) {
                myHand.clear();
                org.json.JSONArray arr = o.optJSONArray("cards");
                if (arr != null) for (int i = 0; i < arr.length(); i++) myHand.add(arr.optString(i));
                renderGame();
            } else if ("ask".equals(e)) {
                showReactDialog(o.optString("kind"), o.optString("from"));
            } else if ("peek".equals(e)) {
                StringBuilder sb = new StringBuilder();
                org.json.JSONArray arr = o.optJSONArray("cards");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    String id = arr.optString(i);
                    sb.append(cardEmoji(id)).append("  ")
                            .append(com.ka0s.pictopals.game.GameEngine.cardName(id)).append('\n');
                }
                new android.app.AlertDialog.Builder(this)
                        .setTitle("👀 " + o.optString("target") + "'s hand")
                        .setMessage(sb.length() == 0 ? "(empty)" : sb.toString())
                        .setPositiveButton("OK", null)
                        .show();
            }
        });
    }

    private void gameSend(JSONObject a) {
        if (isHost) hostServer.gameActionFromHost(a);
        else client.sendGame(a);
    }

    private JSONObject act(String a) {
        JSONObject o = new JSONObject();
        try {
            o.put("t", "game");
            o.put("a", a);
        } catch (Exception ignored) {}
        return o;
    }

    private static String cardEmoji(String id) {
        return com.ka0s.pictopals.game.GameEngine.cardIcon(id);
    }

    /** Accent color per card so a hand is scannable by color as well as icon. */
    private static int cardColor(String id) {
        switch (id) {
            case "clux": return Color.parseColor("#f5a623");   // amber — the lifesaver
            case "pod": return Color.parseColor("#c9a227");    // gold — the win
            case "swap": return Color.parseColor("#3b6ad4");   // blue
            case "block": return Color.parseColor("#12a5a5");  // teal — defensive
            case "thief": return Color.parseColor("#8a3bd4");  // purple — aggressive
            case "freeze": return Color.parseColor("#4fa8d8"); // ice blue
            case "reverse": return Color.parseColor("#e8890c");// orange
            case "stock": return Color.parseColor("#2f9e44");  // green — gain
            case "mooch": return Color.parseColor("#6b8e23");  // olive — gain
            case "peek": return Color.parseColor("#5b6c7d");   // slate — info
            case "dead": return Color.parseColor("#333333");
            case "slips": return Color.parseColor("#8b2e2e");
            default: return Color.DKGRAY;
        }
    }

    /** Short label that fits under a card tile. */
    private static String cardShort(String id) {
        switch (id) {
            case "clux": return "Clux";
            case "mooch": return "Mooch";
            case "freeze": return "Freeze";
            case "thief": return "Thief";
            case "reverse": return "Reverse";
            case "peek": return "Peek";
            case "stock": return "Stock";
            case "swap": return "Swap";
            case "block": return "Block";
            case "pod": return "POD!";
            default: return id;
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String cardDesc(String id) {
        switch (id) {
            case "clux": return "Reverse time 3 minutes. Also saves you from You Dead.";
            case "mooch": return "Take the top useful card from the discard pile instead of drawing.";
            case "freeze": return "A player misses their next turn. (because ice)";
            case "thief": return "Name a card; if the target has it, it's yours.";
            case "reverse": return "Reverse the order of play after your turn.";
            case "peek": return "Look at any player's whole hand.";
            case "stock": return "Draw two cards instead of one.";
            case "swap": return "Trade hands with another player (they can Swap Block).";
            case "block": return "Auto-offered when someone tries to swap hands with you.";
            case "pod": return "Play while the Escape Window is open to WIN THE GAME!";
            default: return "";
        }
    }

    private JSONObject mySeat() {
        if (gameState == null) return null;
        org.json.JSONArray ps = gameState.optJSONArray("players");
        if (ps == null) return null;
        for (int i = 0; i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p != null && myName.equals(p.optString("n"))) return p;
        }
        return null;
    }

    private void addGameText(String text, int sizeSp, boolean bold, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sizeSp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(4, 6, 4, 6);
        gameContent.addView(tv);
    }

    private void addGameButton(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(v -> action.run());
        gameContent.addView(b);
    }

    /** Colors the "what just happened" banner by the kind of moment it was. */
    private static int bannerAccent(String icon) {
        switch (icon) {
            case "🚨": case "☠️": case "👻": case "💥": case "⌛":
                return Color.parseColor("#c62828");
            case "🏆": case "🚀":
                return Color.parseColor("#c9a227");
            case "⚡": case "✨":
                return Color.parseColor("#f5a623");
            case "🔄": case "🛡️":
                return Color.parseColor("#3b6ad4");
            default:
                return Color.parseColor("#2b3f52");
        }
    }

    /** Big icon + one line of what just happened, so nobody has to read the log. */
    private void addBanner(String icon, String text, boolean animate) {
        int accent = bannerAccent(icon);
        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(2), accent);
        banner.setBackground(bg);
        banner.setPadding(dp(12), dp(8), dp(12), dp(8));

        TextView ic = new TextView(this);
        ic.setText(icon);
        ic.setTextSize(34);
        ic.setPadding(0, 0, dp(10), 0);
        banner.addView(ic);

        TextView tx = new TextView(this);
        tx.setText(text);
        tx.setTextSize(14);
        tx.setTextColor(accent);
        tx.setTypeface(null, Typeface.BOLD);
        banner.addView(tx, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(6));
        gameContent.addView(banner, lp);

        if (animate) {
            ic.setScaleX(0.4f);
            ic.setScaleY(0.4f);
            ic.animate().scaleX(1f).scaleY(1f).setDuration(340)
                    .setInterpolator(new android.view.animation.OvershootInterpolator()).start();
            banner.setAlpha(0.25f);
            banner.animate().alpha(1f).setDuration(340).start();
        }
    }

    /** Wraps the whole game panel in a warm border when it's your move. */
    private void setPaneHighlight(boolean on) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(on ? Color.parseColor("#fff6dd") : Color.TRANSPARENT);
        bg.setStroke(on ? dp(3) : 0, on ? Color.parseColor("#f5a623") : Color.TRANSPARENT);
        gameContent.setBackground(bg);
    }

    /** Flags the 🐔 tab button so you notice your turn from the text/draw panes. */
    private void setGameTabAlert(boolean alert) {
        if (modeGameBtn == null) return;
        modeGameBtn.setText(alert ? "🐔 YOUR GO!" : "🐔 Game");
        modeGameBtn.setTextColor(alert ? Color.parseColor("#c62828") : gameBtnDefaultColor);
        if (alert && !wasMyTurn) {
            modeGameBtn.setScaleX(0.8f);
            modeGameBtn.setScaleY(0.8f);
            modeGameBtn.animate().scaleX(1f).scaleY(1f).setDuration(380)
                    .setInterpolator(new android.view.animation.OvershootInterpolator()).start();
        }
        wasMyTurn = alert;
    }

    /** A playing-card-shaped tile: big icon, short name, color-coded border. */
    private View buildCardTile(String id, boolean playable) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        int accent = cardColor(id);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(playable ? 3 : 2), accent);
        tile.setBackground(bg);
        tile.setPadding(dp(6), dp(7), dp(6), dp(6));
        tile.setAlpha(playable ? 1f : 0.55f);

        TextView icon = new TextView(this);
        icon.setText(cardEmoji(id));
        icon.setTextSize(30);
        icon.setGravity(Gravity.CENTER);
        tile.addView(icon);

        TextView nm = new TextView(this);
        nm.setText(cardShort(id));
        nm.setTextSize(10);
        nm.setTextColor(accent);
        nm.setTypeface(null, Typeface.BOLD);
        nm.setGravity(Gravity.CENTER);
        tile.addView(nm);

        tile.setOnClickListener(v -> onCardTap(id, playable));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(68), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(3), dp(2), dp(3), dp(2));
        tile.setLayoutParams(lp);
        return tile;
    }

    private void renderGame() {
        if (gameContent == null) return;
        gameContent.removeAllViews();
        boolean animate = animateBanner;
        animateBanner = false;
        int ink = Color.parseColor("#2b3f52");
        int muted = Color.parseColor("#7a8a99");
        String phase = gameState == null ? "idle" : gameState.optString("phase", "idle");

        if ("idle".equals(phase)) {
            setPaneHighlight(false);
            setGameTabAlert(false);
            addGameText("🐔 Chicken Time Warp — escape the time loop before the multiverse collapses!",
                    14, false, ink);
            if (isHost) {
                addGameButton("🐔 Start a game", () -> gameSend(act("join")));
            } else {
                addGameText("Ask the host to start a game.", 13, false, muted);
            }
            return;
        }

        org.json.JSONArray ps = gameState.optJSONArray("players");
        int n = ps == null ? 0 : ps.length();
        boolean seated = mySeat() != null;
        String lastIcon = gameState.optString("li", "🐔");
        String lastText = gameState.optString("lt", "");

        if ("lobby".equals(phase)) {
            setPaneHighlight(false);
            setGameTabAlert(false);
            addBanner(lastIcon, lastText, animate);
            addGameText("Chickens in the game — " + n + "/6:", 14, true, ink);
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < n; i++) {
                names.append(i > 0 ? ", " : "").append(ps.optJSONObject(i).optString("n"));
            }
            addGameText(names.toString(), 14, false, ink);
            if (!seated) addGameButton("Join the game", () -> gameSend(act("join")));
            else if (!isHost) addGameButton("Leave the game", () -> gameSend(act("leave")));
            if (isHost) {
                addGameButton(n >= 2 ? "▶️ Begin!" : "▶️ Begin (need 2+ players)",
                        () -> gameSend(act("begin")));
                addGameButton("Cancel game", () -> gameSend(act("abort")));
            }
            return;
        }

        boolean over = "over".equals(phase);
        String cur = gameState.optString("cur", "");
        String waiting = gameState.optString("waiting", "");
        boolean ew = gameState.optBoolean("ew");
        JSONObject me = mySeat();
        boolean iAmAlive = me != null && me.optBoolean("alive") && !me.optBoolean("out");
        boolean myTurn = !over && seated && iAmAlive && myName.equals(cur) && waiting.isEmpty();

        setPaneHighlight(myTurn);
        setGameTabAlert(myTurn || myName.equals(waiting));

        // 1. what just happened
        addBanner(lastIcon, lastText, animate);

        // 2. whose move it is
        if (over) {
            String w = gameState.isNull("winner") ? null : gameState.optString("winner", null);
            addGameText(w == null ? "💥 Every chicken is lost — nobody escapes."
                            : "🏆 " + w + " escaped the time loop!", 17, true,
                    w == null ? Color.parseColor("#c62828") : Color.parseColor("#c9a227"));
            if (isHost) addGameButton("🐔 New game", () -> gameSend(act("newgame")));
        } else if (!waiting.isEmpty()) {
            addGameText("⏳ Waiting for " + waiting + " to decide…", 15, true, ink);
        } else if (myTurn) {
            addGameText(ew ? "🚨 YOUR TURN — PLAY THE POD TO WIN!" : "▶️ YOUR TURN — play a card or draw",
                    16, true, ew ? Color.parseColor("#c62828") : Color.parseColor("#b8860b"));
        } else {
            addGameText("▶️ " + cur + "'s turn"
                    + (gameState.optInt("dir", 1) < 0 ? "  🙃 (order reversed)" : ""), 15, true, ink);
        }

        // 3. timeline strip
        LinearLayout tl = new LinearLayout(this);
        tl.setOrientation(LinearLayout.HORIZONTAL);
        org.json.JSONArray slots = gameState.optJSONArray("timeline");
        StringBuilder deadNotes = new StringBuilder();
        int newestUp = -1;
        if (slots != null) {
            for (int i = 0; i < slots.length(); i++) {
                JSONObject slot = slots.optJSONObject(i);
                if (slot != null && "u".equals(slot.optString("s"))) newestUp = i;
            }
            for (int i = 0; i < slots.length(); i++) {
                JSONObject slot = slots.optJSONObject(i);
                int v = slot.optInt("v");
                String s = slot.optString("s");
                TextView cell = new TextView(this);
                cell.setGravity(Gravity.CENTER);
                cell.setTextSize(15);
                cell.setPadding(dp(2), dp(8), dp(2), dp(8));
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(8));
                if ("x".equals(s)) {
                    cell.setText("✕");
                    bg.setColor(Color.parseColor("#b9c3cc"));
                    cell.setTextColor(Color.parseColor("#6b7a88"));
                } else if ("d".equals(s)) {
                    cell.setText("🌀");
                    bg.setColor(Color.parseColor("#2b3f52"));
                    cell.setTextColor(Color.WHITE);
                } else if (v == 0) {
                    cell.setText("🚪");
                    bg.setColor(Color.parseColor("#c62828"));
                    cell.setTextColor(Color.WHITE);
                    cell.setTypeface(null, Typeface.BOLD);
                } else {
                    cell.setText(String.valueOf(v));
                    bg.setColor(Color.WHITE);
                    cell.setTextColor(v <= 3 ? Color.parseColor("#c62828") : ink);
                    cell.setTypeface(null, Typeface.BOLD);
                }
                cell.setBackground(bg);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMargins(dp(1), dp(2), dp(1), dp(2));
                tl.addView(cell, lp);
                // pop the minute that just flipped
                if (animate && i == newestUp
                        && ("⏱️".equals(lastIcon) || "🚨".equals(lastIcon) || "⌛".equals(lastIcon))) {
                    cell.setScaleX(0.5f);
                    cell.setScaleY(0.5f);
                    cell.animate().scaleX(1f).scaleY(1f).setDuration(320)
                            .setInterpolator(new android.view.animation.OvershootInterpolator()).start();
                }
                if (ps != null) {
                    for (int j = 0; j < ps.length(); j++) {
                        JSONObject p = ps.optJSONObject(j);
                        if (p != null && p.optInt("dead", -1) == i) {
                            deadNotes.append("💀 ").append(p.optString("n"))
                                    .append(" clings to ").append(v == 0 ? "the Window" : "minute " + v)
                                    .append("   ");
                        }
                    }
                }
            }
        }
        gameContent.addView(tl);
        if (deadNotes.length() > 0) addGameText(deadNotes.toString().trim(), 12, false, muted);

        // 4. players
        StringBuilder pl = new StringBuilder();
        for (int i = 0; i < n; i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p == null) continue;
            if (i > 0) pl.append("   ");
            if (p.optString("n").equals(cur) && !over) pl.append("▶️");
            if (p.optBoolean("out")) pl.append("👻");
            else if (!p.optBoolean("alive")) pl.append("💀");
            if (p.optBoolean("frozen")) pl.append("🧊");
            if (!p.optBoolean("conn", true)) pl.append("⚠️");
            pl.append(p.optString("n")).append(" 🂠").append(p.optInt("hand"));
        }
        addGameText(pl.toString(), 13, false, ink);
        String top = gameState.isNull("discard") ? null : gameState.optString("discard", null);
        addGameText("🂠 Draw pile: " + gameState.optInt("draw")
                        + (top == null ? "" : "   ♻️ Moochable: " + cardEmoji(top) + " "
                        + com.ka0s.pictopals.game.GameEngine.cardName(top)),
                12, false, muted);

        // 5. my hand
        if (seated && !over) {
            if (!iAmAlive) {
                addGameText(me != null && me.optBoolean("out")
                                ? "👻 You're gone from the multiverse. Spectate and heckle."
                                : "💀 You are dead — a time reversal could still bring you back…",
                        14, true, ink);
            }
            android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout handRow = new LinearLayout(this);
            handRow.setOrientation(LinearLayout.HORIZONTAL);
            for (String id : myHand) {
                handRow.addView(buildCardTile(id, myTurn));
            }
            hs.addView(handRow);
            gameContent.addView(hs);
            if (myTurn) {
                addGameButton("🂠 Just draw & end turn", () -> gameSend(act("pass")));
            } else if (iAmAlive) {
                addGameText("(tap a card to see what it does)", 11, false, muted);
            }
        } else if (!seated && !over) {
            addGameText("👀 You're spectating this game — follow along in the chat log.", 13, false, muted);
        }
        if (isHost && !over) {
            addGameButton("End game (host)", () -> new android.app.AlertDialog.Builder(this)
                    .setMessage("End the game for everyone?")
                    .setPositiveButton("End it", (d, w) -> gameSend(act("abort")))
                    .setNegativeButton("Cancel", null)
                    .show());
        }
    }

    private void onCardTap(String id, boolean myTurn) {
        if (!myTurn) {
            Toast.makeText(this, cardDesc(id), Toast.LENGTH_SHORT).show();
            return;
        }
        if ("block".equals(id)) {
            Toast.makeText(this, "Swap Block plays itself when someone swaps with you.", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean needsTarget = "swap".equals(id) || "thief".equals(id) || "peek".equals(id) || "freeze".equals(id);
        if (!needsTarget) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle(cardEmoji(id) + " " + com.ka0s.pictopals.game.GameEngine.cardName(id))
                    .setMessage(cardDesc(id))
                    .setPositiveButton("Play it", (d, w) -> {
                        JSONObject a = act("play");
                        try { a.put("card", id); } catch (Exception ignored) {}
                        gameSend(a);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        // pick a target
        org.json.JSONArray ps = gameState == null ? null : gameState.optJSONArray("players");
        List<String> targets = new ArrayList<>();
        if (ps != null) for (int i = 0; i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p == null || myName.equals(p.optString("n")) || p.optBoolean("out")) continue;
            if (!"peek".equals(id) && !p.optBoolean("alive")) continue;
            targets.add(p.optString("n"));
        }
        if (targets.isEmpty()) {
            Toast.makeText(this, "No valid targets!", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] arr = targets.toArray(new String[0]);
        new android.app.AlertDialog.Builder(this)
                .setTitle(cardEmoji(id) + " " + com.ka0s.pictopals.game.GameEngine.cardName(id) + " — pick a target")
                .setItems(arr, (d, which) -> {
                    String target = arr[which];
                    if ("thief".equals(id)) {
                        pickWantThenPlay(target);
                    } else {
                        JSONObject a = act("play");
                        try {
                            a.put("card", id);
                            a.put("target", target);
                        } catch (Exception ignored) {}
                        gameSend(a);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void pickWantThenPlay(String target) {
        String[] ids = {"pod", "clux", "swap", "block", "thief", "peek", "freeze", "stock", "mooch", "reverse"};
        String[] labels = new String[ids.length];
        for (int i = 0; i < ids.length; i++) {
            labels[i] = cardEmoji(ids[i]) + " " + com.ka0s.pictopals.game.GameEngine.cardName(ids[i]);
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("🦹 Demand which card from " + target + "?")
                .setItems(labels, (d, which) -> {
                    JSONObject a = act("play");
                    try {
                        a.put("card", "thief");
                        a.put("target", target);
                        a.put("want", ids[which]);
                    } catch (Exception ignored) {}
                    gameSend(a);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showReactDialog(String kind, String from) {
        if (reactDialog != null) reactDialog.dismiss();
        boolean isClux = "clux".equals(kind);
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                .setCancelable(false)
                .setTitle(isClux ? "☠️ YOU DEAD!" : "🔄 Incoming swap!")
                .setMessage(isClux
                        ? "You drew You Dead! Burn a Clux Capacitor to reverse time and survive? (auto-yes in 30s)"
                        : from + " is trying to swap hands with you! Play your Swap Block? (auto-allow in 30s)")
                .setPositiveButton(isClux ? "⚡ Use it!" : "🛡 Block it!", (d, w) -> {
                    JSONObject a = act("react");
                    try { a.put("yes", true); } catch (Exception ignored) {}
                    gameSend(a);
                    reactDialog = null;
                })
                .setNegativeButton(isClux ? "Accept death" : "Let it happen", (d, w) -> {
                    JSONObject a = act("react");
                    try { a.put("yes", false); } catch (Exception ignored) {}
                    gameSend(a);
                    reactDialog = null;
                });
        reactDialog = b.show();
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
            } else if (m.results != null) {
                StringBuilder sb = new StringBuilder("🎲 rolled ");
                if (m.results.length > 1) sb.append(m.results.length).append("× ");
                sb.append("D").append(m.die).append(" …  ");
                int total = 0;
                boolean crit = false;
                for (int i = 0; i < m.results.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(m.results[i]);
                    total += m.results[i];
                    if (m.die == 20 && m.results[i] == 20) crit = true;
                }
                if (m.results.length > 1) sb.append("  (total ").append(total).append(")");
                if (crit) sb.append("  💥 CRIT!");
                TextView tv = new TextView(ctx);
                tv.setText(sb.toString());
                tv.setTextColor(crit ? Color.parseColor("#c62828") : Color.parseColor("#2b3f52"));
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
