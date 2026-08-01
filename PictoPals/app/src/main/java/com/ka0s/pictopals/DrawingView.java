package com.ka0s.pictopals;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.io.ByteArrayOutputStream;

/**
 * The PictoChat-style message composer: a small white panel you draw on with a
 * finger or stylus. Strokes go straight onto a backing bitmap which is sent as
 * a PNG when the user hits SEND.
 */
public class DrawingView extends View {

    private static final int BMP_W = 480;

    private Bitmap bitmap;
    private Canvas bmpCanvas;
    private final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float lastBx, lastBy;
    private boolean drawing = false;
    private boolean dirty = false;

    public static final int TOOL_PEN = 0;
    public static final int TOOL_BIG = 1;
    public static final int TOOL_ERASER = 2;
    private int tool = TOOL_PEN;

    public DrawingView(Context c, AttributeSet a) {
        super(c, a);
        pen.setStyle(Paint.Style.STROKE);
        pen.setStrokeCap(Paint.Cap.ROUND);
        pen.setStrokeJoin(Paint.Join.ROUND);
        applyTool();
    }

    public void setTool(int t) {
        tool = t;
        applyTool();
    }

    public int getTool() {
        return tool;
    }

    private void applyTool() {
        if (tool == TOOL_ERASER) {
            pen.setColor(Color.WHITE);
            pen.setStrokeWidth(26f);
        } else {
            pen.setColor(Color.BLACK);
            pen.setStrokeWidth(tool == TOOL_BIG ? 9f : 4f);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w <= 0 || h <= 0) return;
        int bh = Math.max(96, Math.min(260, Math.round(BMP_W * (float) h / w)));
        Bitmap nb = Bitmap.createBitmap(BMP_W, bh, Bitmap.Config.ARGB_8888);
        Canvas nc = new Canvas(nb);
        nc.drawColor(Color.WHITE);
        if (bitmap != null) {
            nc.drawBitmap(bitmap, null, new Rect(0, 0, BMP_W, bh), null);
        }
        bitmap = nb;
        bmpCanvas = nc;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, null, new Rect(0, 0, getWidth(), getHeight()), null);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (bitmap == null) return false;
        float bx = e.getX() * bitmap.getWidth() / getWidth();
        float by = e.getY() * bitmap.getHeight() / getHeight();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                drawing = true;
                lastBx = bx;
                lastBy = by;
                bmpCanvas.drawPoint(bx, by, pen);
                dirty = true;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (drawing) {
                    for (int i = 0; i < e.getHistorySize(); i++) {
                        float hx = e.getHistoricalX(i) * bitmap.getWidth() / getWidth();
                        float hy = e.getHistoricalY(i) * bitmap.getHeight() / getHeight();
                        bmpCanvas.drawLine(lastBx, lastBy, hx, hy, pen);
                        lastBx = hx;
                        lastBy = hy;
                    }
                    bmpCanvas.drawLine(lastBx, lastBy, bx, by, pen);
                    lastBx = bx;
                    lastBy = by;
                    dirty = true;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                drawing = false;
                return true;
        }
        return super.onTouchEvent(e);
    }

    public void clear() {
        if (bmpCanvas != null) bmpCanvas.drawColor(Color.WHITE);
        dirty = false;
        invalidate();
    }

    public boolean hasContent() {
        return dirty;
    }

    public byte[] getPngBytes() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos);
        return bos.toByteArray();
    }
}
