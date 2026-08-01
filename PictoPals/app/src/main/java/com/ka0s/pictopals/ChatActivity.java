package com.ka0s.pictopals;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** The chat room: message history on top, drawing panel + tools at the bottom. */
public class ChatActivity extends Activity implements HostServer.Listener, ChatClient.Listener {

    private static class Msg {
        String name, color, sysText;
        Bitmap bitmap;
    }

    private final List<Msg> messages = new ArrayList<>();
    private MsgAdapter adapter;
    private DrawingView drawView;

    private HostServer hostServer;
    private ChatClient client;
    private boolean isHost;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
        ListView list = findViewById(R.id.msgList);
        adapter = new MsgAdapter();
        list.setAdapter(adapter);

        Button penBtn = findViewById(R.id.penBtn);
        Button bigBtn = findViewById(R.id.bigBtn);
        Button eraseBtn = findViewById(R.id.eraseBtn);
        View[] toolBtns = {penBtn, bigBtn, eraseBtn};
        penBtn.setOnClickListener(v -> selectTool(DrawingView.TOOL_PEN, toolBtns, v));
        bigBtn.setOnClickListener(v -> selectTool(DrawingView.TOOL_BIG, toolBtns, v));
        eraseBtn.setOnClickListener(v -> selectTool(DrawingView.TOOL_ERASER, toolBtns, v));
        selectTool(DrawingView.TOOL_PEN, toolBtns, penBtn);

        findViewById(R.id.textBtn).setOnClickListener(v -> promptText());
        findViewById(R.id.clearBtn).setOnClickListener(v -> drawView.clear());
        findViewById(R.id.leaveBtn).setOnClickListener(v -> finish());

        findViewById(R.id.sendBtn).setOnClickListener(v -> {
            if (!drawView.hasContent()) {
                Toast.makeText(this, "Draw or type something first!", Toast.LENGTH_SHORT).show();
                return;
            }
            byte[] png = drawView.getPngBytes();
            if (isHost) {
                hostServer.sendFromHost(png);
            } else {
                client.send(png);
            }
            drawView.clear();
        });

        if (isHost) {
            hostServer = new HostServer(room, name, color, this);
            try {
                hostServer.start();
                onSys("You are hosting Room " + room + ". Others on your hotspot/WiFi will see it appear.");
            } catch (IOException e) {
                Toast.makeText(this, "Couldn't host: is another room already hosted on this phone?",
                        Toast.LENGTH_LONG).show();
                finish();
            }
        } else {
            client = new ChatClient(this);
            client.connect(getIntent().getStringExtra("ip"),
                    getIntent().getIntExtra("port", Proto.TCP_PORT), name, color);
            onSys("Joining…");
        }
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

    private void selectTool(int tool, View[] toolBtns, View active) {
        drawView.setTool(tool);
        for (View b : toolBtns) b.setAlpha(b == active ? 1f : 0.45f);
    }

    private void promptText() {
        EditText input = new EditText(this);
        input.setHint("Type a message to stamp onto the panel");
        new AlertDialog.Builder(this)
                .setTitle("Add text")
                .setView(input)
                .setPositiveButton("Stamp", (d, w) -> drawView.stampText(input.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (hostServer != null) hostServer.stop();
        if (client != null) client.close();
    }

    // ---- network callbacks (arrive on background threads) ----

    @Override
    public void onMsg(String name, String color, byte[] png) {
        Bitmap bmp = BitmapFactory.decodeByteArray(png, 0, png.length);
        if (bmp == null) return;
        runOnUiThread(() -> {
            Msg m = new Msg();
            m.name = name;
            m.color = color;
            m.bitmap = bmp;
            messages.add(m);
            adapter.notifyDataSetChanged();
        });
    }

    @Override
    public void onSys(String text) {
        runOnUiThread(() -> {
            Msg m = new Msg();
            m.sysText = text;
            messages.add(m);
            adapter.notifyDataSetChanged();
        });
    }

    @Override
    public void onClosed(String reason) {
        runOnUiThread(() -> {
            Toast.makeText(this, reason, Toast.LENGTH_LONG).show();
            finish();
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
            // Views vary a lot (system vs message); rebuild each time — counts stay small.
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
            nameTv.setTypeface(null, android.graphics.Typeface.BOLD);
            nameTv.setPadding(16, 3, 16, 3);
            GradientDrawable nameBg = new GradientDrawable();
            nameBg.setColor(frameColor);
            nameBg.setCornerRadii(new float[]{10f, 10f, 0f, 0f, 10f, 0f, 0f, 0f});
            nameTv.setBackground(nameBg);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            box.addView(nameTv, nameLp);

            ImageView iv = new ImageView(ctx);
            iv.setImageBitmap(m.bitmap);
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            int w = parent.getWidth() > 0 ? parent.getWidth() - 40 : 800;
            int h = Math.round(w * (float) m.bitmap.getHeight() / m.bitmap.getWidth());
            box.addView(iv, new LinearLayout.LayoutParams(w, h));

            return box;
        }
    }
}
