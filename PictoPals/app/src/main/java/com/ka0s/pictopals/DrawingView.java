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
import java.util.ArrayList;
import java.util.List;

/**
 * The PictoChat-style message composer: a small white panel you draw on with a
 * finger or stylus. Strokes are kept as a list (enabling undo) and rendered
 * onto a backing bitmap which is sent as a PNG when the user hits SEND.
 */
public class DrawingView extends View {

    private static final int BMP_W = 480;

    /** One continuous finger-down-to-up stroke with the paint it was drawn with. */
    private static class Stroke {
        final Paint paint;
        final List<float[]> pts = new ArrayList<>();

        Stroke(Paint source) {
            paint = new Paint(source);
        }
    }

    private Bitmap bitmap;
    private Canvas bmpCanvas;
    private final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Stroke> strokes = new ArrayList<>();
    private Stroke current;
    private float lastBx, lastBy;
    private int penColor = Color.BLACK;

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

    /** Pens draw in the user's chat color; the eraser is unaffected. */
    public void setPenColor(int color) {
        penColor = color;
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
            pen.setColor(penColor);
            pen.setStrokeWidth(tool == TOOL_BIG ? 9f : 4f);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w <= 0 || h <= 0) return;
        int bh = Math.max(96, Math.min(300, Math.round(BMP_W * (float) h / w)));
        bitmap = Bitmap.createBitmap(BMP_W, bh, Bitmap.Config.ARGB_8888);
        bmpCanvas = new Canvas(bitmap);
        redrawAll();
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
                current = new Stroke(pen);
                current.pts.add(new float[]{bx, by});
                lastBx = bx;
                lastBy = by;
                bmpCanvas.drawPoint(bx, by, pen);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (current != null) {
                    for (int i = 0; i < e.getHistorySize(); i++) {
                        float hx = e.getHistoricalX(i) * bitmap.getWidth() / getWidth();
                        float hy = e.getHistoricalY(i) * bitmap.getHeight() / getHeight();
                        addSegment(hx, hy);
                    }
                    addSegment(bx, by);
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (current != null) {
                    strokes.add(current);
                    current = null;
                }
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void addSegment(float bx, float by) {
        bmpCanvas.drawLine(lastBx, lastBy, bx, by, pen);
        current.pts.add(new float[]{bx, by});
        lastBx = bx;
        lastBy = by;
    }

    /** Removes the most recent stroke and re-renders the rest. */
    public void undo() {
        if (!strokes.isEmpty()) {
            strokes.remove(strokes.size() - 1);
            redrawAll();
        }
    }

    private void redrawAll() {
        if (bmpCanvas == null) return;
        bmpCanvas.drawColor(Color.WHITE);
        for (Stroke s : strokes) {
            if (s.pts.size() == 1) {
                float[] p = s.pts.get(0);
                bmpCanvas.drawPoint(p[0], p[1], s.paint);
            } else {
                for (int i = 1; i < s.pts.size(); i++) {
                    float[] a = s.pts.get(i - 1);
                    float[] b = s.pts.get(i);
                    bmpCanvas.drawLine(a[0], a[1], b[0], b[1], s.paint);
                }
            }
        }
        invalidate();
    }

    public void clear() {
        strokes.clear();
        current = null;
        if (bmpCanvas != null) bmpCanvas.drawColor(Color.WHITE);
        invalidate();
    }

    public boolean hasContent() {
        return !strokes.isEmpty() || current != null;
    }

    public byte[] getPngBytes() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos);
        return bos.toByteArray();
    }
}
