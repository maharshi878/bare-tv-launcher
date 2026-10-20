package dev.bare.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;

/**
 * Card artwork, drawn straight onto a canvas so the same code paints tiles, the hero
 * backdrop and the colour sampling. In order of preference: the app's TV banner, its
 * adaptive icon spread edge to edge, or its icon on a plate tinted from its own colour.
 */
final class Art {
    private Art() { }

    private static final Paint FILL = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint TEXT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private static final Rect OLD = new Rect();

    static void draw(Canvas c, Entry e, int w, int h) {
        switch (e.kind) {
            case Entry.ADD:
                plate(c, w, h, 0xFF34343A, 0xFF24242A);
                glyph(c, Glyph.PLUS, 0xD9FFFFFF, w, h, 0.36f);
                return;
            case Entry.SETTINGS:
                plate(c, w, h, 0xFF8E8E95, 0xFF4C4C52);
                glyph(c, Glyph.GEAR, 0xFFFFFFFF, w, h, 0.5f);
                return;
            case Entry.INPUTS:
                plate(c, w, h, 0xFF3F5A8C, 0xFF1C2741);
                glyph(c, Glyph.INPUT, 0xFFFFFFFF, w, h, 0.44f);
                return;
        }

        if (e.banner != null) {
            cover(c, e.banner, w, h);
            return;
        }

        if (e.icon != null && Build.VERSION.SDK_INT >= 26 && e.icon instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) e.icon;
            Drawable bg = a.getBackground(), fg = a.getForeground();
            if (bg != null) {
                // The background layer is a square; let it cover the card.
                int s = Math.max(w, h);
                place(c, bg, (w - s) / 2, (h - s) / 2, s);
            } else {
                plate(c, w, h, lift(glow(e), 0.12f), shade(glow(e), 0.35f));
            }
            // 108-unit layer, 66-unit safe zone: sized so the logo reads at ~70% of the height.
            if (fg != null) { int s = Math.round(h * 1.12f); place(c, fg, (w - s) / 2, (h - s) / 2, s); }
            return;
        }

        int g = glow(e);
        plate(c, w, h, lift(g, 0.1f), shade(g, 0.42f));
        if (e.icon != null) {
            int s = Math.round(h * 0.54f);
            place(c, e.icon, (w - s) / 2, (h - s) / 2, s);
        } else if (e.kind == Entry.CUSTOM && e.launch != null && e.launch.getData() != null) {
            glyph(c, Glyph.GLOBE, 0xF2FFFFFF, w, h, 0.4f);
        } else {
            String l = e.label == null || e.label.isEmpty() ? "?" : e.label.substring(0, 1).toUpperCase();
            TEXT.setColor(0xF2FFFFFF);
            TEXT.setTextAlign(Paint.Align.CENTER);
            TEXT.setTextSize(h * 0.46f);
            TEXT.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            Paint.FontMetrics fm = TEXT.getFontMetrics();
            c.drawText(l, w / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, TEXT);
        }
    }

    // ---------------------------------------------------------------- pieces

    private static void plate(Canvas c, int w, int h, int top, int bottom) {
        FILL.setColor(0xFF000000);   // full opacity; a shader is drawn at the paint's alpha
        FILL.setShader(new LinearGradient(0, 0, w * 0.5f, h, top, bottom, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, FILL);
        FILL.setShader(null);
    }

    private static void glyph(Canvas c, int type, int color, int w, int h, float frac) {
        float s = h * frac;
        Glyph.draw(c, type, color, (w - s) / 2f, (h - s) / 2f, s);
    }

    /** Centre-crops {@code d} into the card. */
    private static void cover(Canvas c, Drawable d, int w, int h) {
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        if (iw <= 0 || ih <= 0) { iw = 16; ih = 9; }
        float s = Math.max(w / (float) iw, h / (float) ih);
        int dw = Math.round(iw * s), dh = Math.round(ih * s);
        d.copyBounds(OLD);
        d.setBounds((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh);
        d.draw(c);
        d.setBounds(OLD);
    }

    private static void place(Canvas c, Drawable d, int x, int y, int s) {
        d.copyBounds(OLD);
        d.setBounds(x, y, x + s, y + s);
        d.draw(c);
        d.setBounds(OLD);
    }

    // ---------------------------------------------------------------- colour

    /** A vivid, mid-dark version of the artwork's main colour. Cached on the entry. */
    static int glow(Entry e) {
        if (e.glow != 0) return e.glow;
        Drawable art = e.banner != null ? e.banner : e.icon;
        int out = 0;
        if (art != null) {
            try {
                Bitmap b = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(b);
                if (e.banner != null) cover(c, art, 24, 24); else place(c, art, 0, 0, 24);
                // Vote by hue family, weighted by how vivid each pixel is, then average the
                // winning family. Averaging everything turns multicoloured logos to mud.
                float[] r = new float[12], g = new float[12], bl = new float[12], wsum = new float[12];
                float[] hsv = new float[3];
                for (int y = 0; y < 24; y++) for (int x = 0; x < 24; x++) {
                    int p = b.getPixel(x, y);
                    if (Color.alpha(p) < 128) continue;
                    Color.colorToHSV(p, hsv);
                    float wt = hsv[1] * hsv[2];
                    if (wt < 0.12f) continue;
                    wt *= wt;
                    int k = ((int) (hsv[0] / 30f)) % 12;
                    r[k] += Color.red(p) * wt; g[k] += Color.green(p) * wt; bl[k] += Color.blue(p) * wt; wsum[k] += wt;
                }
                b.recycle();
                int best = 0;
                for (int k = 1; k < 12; k++) if (wsum[k] > wsum[best]) best = k;
                if (wsum[best] > 0.05f) {
                    Color.colorToHSV(Color.rgb((int) (r[best] / wsum[best]), (int) (g[best] / wsum[best]),
                            (int) (bl[best] / wsum[best])), hsv);
                    hsv[1] = Math.min(0.8f, Math.max(0.45f, hsv[1]));
                    hsv[2] = 0.62f;
                    out = Color.HSVToColor(hsv);
                }
            } catch (RuntimeException ignored) { }
        }
        if (out == 0) {
            String seed = e.label == null ? "" : e.label;
            out = Color.HSVToColor(new float[] { Math.abs(seed.hashCode() % 360), 0.45f, 0.55f });
        }
        return e.glow = out;
    }

    static int lift(int c, float t) { return mix(0xFFFFFFFF, c, t); }
    static int shade(int c, float t) { return mix(0xFF000000, c, t); }

    static int mix(int a, int b, float t) {
        return Color.rgb(
                Math.round(Color.red(a) * t + Color.red(b) * (1 - t)),
                Math.round(Color.green(a) * t + Color.green(b) * (1 - t)),
                Math.round(Color.blue(a) * t + Color.blue(b) * (1 - t)));
    }

    // ---------------------------------------------------------------- ambient

    /** A 64x36 render of the card, heavily blurred. Upscaled, it becomes the backdrop. */
    static Bitmap ambient(Entry e) {
        if (e.ambient != null) return e.ambient;
        final int w = 64, h = 36;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFF000000);
        try { draw(c, e, w, h); } catch (RuntimeException ignored) { }
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) blur(px, w, h, 4);
        b.setPixels(px, 0, w, 0, 0, w, h);
        return e.ambient = b;
    }

    /** One separable box-blur pass, edges clamped. Plenty for a 64x36 image. */
    private static void blur(int[] px, int w, int h, int r) {
        int[] tmp = new int[px.length];
        for (int pass = 0; pass < 2; pass++) {
            int[] src = pass == 0 ? px : tmp, dst = pass == 0 ? tmp : px;
            int len = pass == 0 ? w : h, lines = pass == 0 ? h : w;
            for (int line = 0; line < lines; line++) {
                for (int i = 0; i < len; i++) {
                    int rs = 0, gs = 0, bs = 0, n = 0;
                    for (int k = -r; k <= r; k++) {
                        int j = Math.min(len - 1, Math.max(0, i + k));
                        int p = pass == 0 ? src[line * w + j] : src[j * w + line];
                        rs += (p >> 16) & 0xFF; gs += (p >> 8) & 0xFF; bs += p & 0xFF; n++;
                    }
                    int out = 0xFF000000 | ((rs / n) << 16) | ((gs / n) << 8) | (bs / n);
                    if (pass == 0) dst[line * w + i] = out; else dst[i * w + line] = out;
                }
            }
        }
    }
}
