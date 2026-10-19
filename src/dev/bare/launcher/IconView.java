package dev.bare.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** The handful of line icons the top bar needs, drawn on a 24-unit grid. No assets. */
final class IconView extends View {
    static final int SEARCH = 0, MIC = 1, GEAR = 2, INPUT = 3, WIFI = 4;

    private final int type;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path gear = new Path();
    private int color = 0xFFE3E3E8;
    private boolean slash;

    IconView(Context c, int type) {
        super(c);
        this.type = type;
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        if (type == GEAR) buildGear();
    }

    void setColor(int c) { color = c; invalidate(); }
    void setSlash(boolean s) { slash = s; invalidate(); }

    private void buildGear() {
        Path body = new Path();
        body.addCircle(12, 12, 7.1f, Path.Direction.CW);
        Path tooth = new Path();
        tooth.addRoundRect(new RectF(10.2f, 2.4f, 13.8f, 7.4f), 1f, 1f, Path.Direction.CW);
        for (int k = 0; k < 8; k++) {
            Matrix m = new Matrix();
            m.setRotate(k * 45f, 12, 12);
            body.addPath(tooth, m);
        }
        Path hole = new Path();
        hole.addCircle(12, 12, 3.1f, Path.Direction.CW);
        if (body.op(hole, Path.Op.DIFFERENCE)) gear.set(body); else gear.set(body);
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight()) / 24f;
        c.save();
        c.translate((getWidth() - 24 * s) / 2f, (getHeight() - 24 * s) / 2f);
        c.scale(s, s);
        p.setColor(color);
        p.setStrokeWidth(2f);
        switch (type) {
            case SEARCH:
                p.setStyle(Paint.Style.STROKE);
                c.drawCircle(10.2f, 10.2f, 6.2f, p);
                c.drawLine(14.8f, 14.8f, 20f, 20f, p);
                break;
            case MIC: {
                p.setStyle(Paint.Style.FILL);
                c.drawRoundRect(new RectF(9f, 2.5f, 15f, 14f), 3f, 3f, p);
                p.setStyle(Paint.Style.STROKE);
                Path a = new Path();
                a.moveTo(5.8f, 11f);
                a.arcTo(new RectF(5.8f, 5.4f, 18.2f, 17.8f), 180f, -180f);
                c.drawPath(a, p);
                c.drawLine(12f, 17.8f, 12f, 21.2f, p);
                c.drawLine(8.8f, 21.2f, 15.2f, 21.2f, p);
                break;
            }
            case GEAR:
                p.setStyle(Paint.Style.FILL);
                c.drawPath(gear, p);
                break;
            case INPUT: {
                p.setStyle(Paint.Style.STROKE);
                Path b = new Path();
                b.moveTo(14.5f, 4f); b.lineTo(20f, 4f); b.lineTo(20f, 20f); b.lineTo(14.5f, 20f);
                c.drawPath(b, p);
                c.drawLine(3.5f, 12f, 15f, 12f, p);
                Path h = new Path();
                h.moveTo(11f, 8f); h.lineTo(15f, 12f); h.lineTo(11f, 16f);
                c.drawPath(h, p);
                break;
            }
            case WIFI: {
                p.setStyle(Paint.Style.STROKE);
                for (float r : new float[] { 5f, 9f, 13f }) {
                    c.drawArc(new RectF(12 - r, 19 - r, 12 + r, 19 + r), 225f, 90f, false, p);
                }
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(12f, 19f, 1.7f, p);
                if (slash) {
                    p.setStyle(Paint.Style.STROKE);
                    c.drawLine(4f, 4f, 20f, 20f, p);
                }
                break;
            }
        }
        c.restore();
    }
}
