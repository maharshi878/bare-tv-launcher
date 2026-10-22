package dev.bare.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Line icons drawn on a 24-unit grid. No assets; they stay crisp at any density. */
final class Glyph extends View {
    static final int GEAR = 0, INPUT = 1, WIFI_OFF = 2, PLUS = 3, INFO = 4, HIDE = 5, SHOW = 6,
            TRASH = 7, MOVE = 8, TOP = 9, CHECK = 10, GLOBE = 11, SOUND = 12, PHOTO = 13, HOME = 14,
            SLIDERS = 15, DOCK = 16, TV = 17;

    private static final Paint P = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path GEAR_PATH = gear();
    static {
        P.setStrokeCap(Paint.Cap.ROUND);
        P.setStrokeJoin(Paint.Join.ROUND);
    }

    private int type, color = 0xFFFFFFFF;

    Glyph(Context c, int type) { super(c); this.type = type; }

    void setType(int t) { type = t; invalidate(); }
    void setColor(int c) { color = c; invalidate(); }

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight());
        draw(c, type, color, (getWidth() - s) / 2f, (getHeight() - s) / 2f, s);
    }

    /** Draws glyph {@code type} into the square at (x, y) with side {@code size}. */
    static void draw(Canvas c, int type, int color, float x, float y, float size) {
        c.save();
        c.translate(x, y);
        c.scale(size / 24f, size / 24f);
        P.setColor(color);
        P.setStrokeWidth(1.9f);
        P.setStyle(Paint.Style.STROKE);
        switch (type) {
            case GEAR:
                P.setStyle(Paint.Style.FILL);
                c.drawPath(GEAR_PATH, P);
                break;
            case INPUT: {
                Path b = new Path();
                b.moveTo(14f, 4.5f); b.lineTo(18.5f, 4.5f); b.quadTo(20f, 4.5f, 20f, 6f);
                b.lineTo(20f, 18f); b.quadTo(20f, 19.5f, 18.5f, 19.5f); b.lineTo(14f, 19.5f);
                c.drawPath(b, P);
                c.drawLine(4f, 12f, 14.5f, 12f, P);
                Path h = new Path();
                h.moveTo(10.5f, 8f); h.lineTo(14.5f, 12f); h.lineTo(10.5f, 16f);
                c.drawPath(h, P);
                break;
            }
            case WIFI_OFF:
                for (float r : new float[] { 5f, 9f, 13f }) {
                    c.drawArc(new RectF(12 - r, 19 - r, 12 + r, 19 + r), 225f, 90f, false, P);
                }
                P.setStyle(Paint.Style.FILL);
                c.drawCircle(12f, 19f, 1.6f, P);
                P.setStyle(Paint.Style.STROKE);
                c.drawLine(4f, 4f, 20f, 20f, P);
                break;
            case PLUS:
                c.drawLine(12f, 5f, 12f, 19f, P);
                c.drawLine(5f, 12f, 19f, 12f, P);
                break;
            case INFO:
                c.drawCircle(12f, 12f, 9f, P);
                c.drawLine(12f, 11f, 12f, 16.5f, P);
                P.setStyle(Paint.Style.FILL);
                c.drawCircle(12f, 7.7f, 1.2f, P);
                break;
            case HIDE:
            case SHOW: {
                Path eye = new Path();
                eye.moveTo(2.5f, 12f);
                eye.quadTo(12f, 3f, 21.5f, 12f);
                eye.quadTo(12f, 21f, 2.5f, 12f);
                c.drawPath(eye, P);
                c.drawCircle(12f, 12f, 3f, P);
                if (type == HIDE) c.drawLine(4f, 4f, 20f, 20f, P);
                break;
            }
            case TRASH: {
                c.drawLine(4.5f, 6.5f, 19.5f, 6.5f, P);
                Path h = new Path();
                h.moveTo(9.5f, 6.5f); h.lineTo(9.5f, 4.5f); h.lineTo(14.5f, 4.5f); h.lineTo(14.5f, 6.5f);
                c.drawPath(h, P);
                Path b = new Path();
                b.moveTo(6.5f, 6.5f); b.lineTo(7.5f, 19.5f); b.lineTo(16.5f, 19.5f); b.lineTo(17.5f, 6.5f);
                c.drawPath(b, P);
                c.drawLine(10.2f, 10f, 10.2f, 16f, P);
                c.drawLine(13.8f, 10f, 13.8f, 16f, P);
                break;
            }
            case MOVE: {
                c.drawLine(12f, 3.5f, 12f, 20.5f, P);
                c.drawLine(3.5f, 12f, 20.5f, 12f, P);
                Path a = new Path();
                a.moveTo(9.5f, 6f); a.lineTo(12f, 3.5f); a.lineTo(14.5f, 6f);
                a.moveTo(9.5f, 18f); a.lineTo(12f, 20.5f); a.lineTo(14.5f, 18f);
                a.moveTo(6f, 9.5f); a.lineTo(3.5f, 12f); a.lineTo(6f, 14.5f);
                a.moveTo(18f, 9.5f); a.lineTo(20.5f, 12f); a.lineTo(18f, 14.5f);
                c.drawPath(a, P);
                break;
            }
            case TOP: {
                c.drawLine(5f, 4.5f, 19f, 4.5f, P);
                c.drawLine(12f, 20f, 12f, 9f, P);
                Path a = new Path();
                a.moveTo(7.5f, 13.5f); a.lineTo(12f, 9f); a.lineTo(16.5f, 13.5f);
                c.drawPath(a, P);
                break;
            }
            case CHECK: {
                Path a = new Path();
                a.moveTo(5f, 12.5f); a.lineTo(10f, 17.5f); a.lineTo(19f, 7f);
                c.drawPath(a, P);
                break;
            }
            case GLOBE:
                c.drawCircle(12f, 12f, 9f, P);
                c.drawOval(new RectF(8f, 3f, 16f, 21f), P);
                c.drawLine(3f, 12f, 21f, 12f, P);
                break;
            case SOUND: {
                Path b = new Path();
                b.moveTo(3.5f, 9.5f); b.lineTo(7.5f, 9.5f); b.lineTo(12f, 5f); b.lineTo(12f, 19f);
                b.lineTo(7.5f, 14.5f); b.lineTo(3.5f, 14.5f); b.close();
                c.drawPath(b, P);
                c.drawArc(new RectF(10f, 8.5f, 17f, 15.5f), -50f, 100f, false, P);
                c.drawArc(new RectF(9f, 5f, 21f, 19f), -50f, 100f, false, P);
                break;
            }
            case PHOTO: {
                c.drawRoundRect(new RectF(3f, 5f, 21f, 19f), 2.5f, 2.5f, P);
                Path m = new Path();
                m.moveTo(3.5f, 17f); m.lineTo(9f, 11.5f); m.lineTo(13f, 15.5f); m.lineTo(15.5f, 13f); m.lineTo(20.5f, 18f);
                c.drawPath(m, P);
                P.setStyle(Paint.Style.FILL);
                c.drawCircle(16f, 9.5f, 1.6f, P);
                break;
            }
            case HOME: {
                Path h = new Path();
                h.moveTo(3.5f, 11.5f); h.lineTo(12f, 4f); h.lineTo(20.5f, 11.5f);
                h.moveTo(6f, 9.5f); h.lineTo(6f, 20f); h.lineTo(18f, 20f); h.lineTo(18f, 9.5f);
                c.drawPath(h, P);
                break;
            }
            case SLIDERS:
                for (float[] k : new float[][] { { 7f, 9f }, { 12f, 15f }, { 17f, 8f } }) {
                    c.drawLine(4f, k[0], k[1] - 2.4f, k[0], P);
                    c.drawLine(k[1] + 2.4f, k[0], 20f, k[0], P);
                    c.drawCircle(k[1], k[0], 2.2f, P);
                }
                break;
            case DOCK:
                c.drawRoundRect(new RectF(2.5f, 4.5f, 21.5f, 18.5f), 2.5f, 2.5f, P);
                c.drawRoundRect(new RectF(6.5f, 13.5f, 17.5f, 15.5f), 1f, 1f, P);
                break;
            case TV:
                c.drawRoundRect(new RectF(3f, 5f, 21f, 17f), 2.5f, 2.5f, P);
                c.drawLine(8.5f, 20.5f, 15.5f, 20.5f, P);
                break;
        }
        c.restore();
    }

    private static Path gear() {
        Path body = new Path();
        body.addCircle(12, 12, 7.2f, Path.Direction.CW);
        Path tooth = new Path();
        tooth.addRoundRect(new RectF(10.1f, 2.2f, 13.9f, 7.4f), 1.1f, 1.1f, Path.Direction.CW);
        for (int k = 0; k < 8; k++) {
            Matrix m = new Matrix();
            m.setRotate(k * 45f, 12, 12);
            body.addPath(tooth, m);
        }
        Path hole = new Path();
        hole.addCircle(12, 12, 3.2f, Path.Direction.CW);
        body.op(hole, Path.Op.DIFFERENCE);
        return body;
    }
}
