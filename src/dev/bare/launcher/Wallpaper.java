package dev.bare.launcher;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The picture behind everything: a full-screen photo from assets/wallpapers (listed with
 * their place and photographer in index.txt), a frosted copy of it for when the grid is in
 * front, and a scrim that keeps the clock and labels readable. Photos are decoded off the main
 * thread at screen size; changing one is a crossfade of two layers, so the GPU only composites.
 * Without any photos, a generated dusk gradient stands in.
 */
final class Wallpaper extends FrameLayout {
    static final int BASE = 0xFF0B0B0E;

    /** For the screenshot harness: decode on the calling thread, so frames are deterministic. */
    static boolean sync;

    static final class Photo {
        final String file, place, credit;
        Photo(String file, String place, String credit) { this.file = file; this.place = place; this.credit = credit; }
    }

    private final Pane[] panes = new Pane[2];
    private final View corner, floor;
    private final List<Photo> photos;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int front, loading = -1;
    private Photo cur;
    private float depth, calm;

    Wallpaper(Context c) {
        super(c);
        setBackgroundColor(BASE);
        for (int i = 0; i < 2; i++) {
            Pane p = new Pane(c);
            p.setAlpha(0f);
            addView(p, new LayoutParams(-1, -1));
            panes[i] = p;
        }
        // Darker behind the clock (top left) and the apps (bottom); the middle stays clear. Two
        // views covering only those regions, so the GPU doesn't blend the whole screen twice.
        int w = c.getResources().getDisplayMetrics().widthPixels, h = c.getResources().getDisplayMetrics().heightPixels;
        corner = new Corner(c);
        addView(corner, new LayoutParams(Math.round(w * 0.62f), Math.round(h * 0.55f)));
        floor = new View(c);
        floor.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { 0x00000000, 0x33000000, 0xA6000000 }));
        addView(floor, new LayoutParams(-1, Math.round(h * 0.5f), android.view.Gravity.BOTTOM));
        photos = list(c.getAssets());
    }

    int count() { return Math.max(1, photos.size()); }
    Photo current() { return cur; }

    /** Shows photo {@code i} (wrapping), crossfading from the one before. */
    void show(int i) {
        final int index = photos.isEmpty() ? 0 : ((i % photos.size()) + photos.size()) % photos.size();
        if (index == loading) return;
        loading = index;
        final int w = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        final int h = Math.max(1, getResources().getDisplayMetrics().heightPixels);
        final Photo p = photos.isEmpty() ? null : photos.get(index);
        Runnable work = () -> {
            Bitmap sharp = null;
            if (p != null) {
                try { sharp = decode(getContext().getAssets(), "wallpapers/" + p.file, w, h); }
                catch (IOException | RuntimeException | OutOfMemoryError ignored) { }
            }
            if (sharp == null) sharp = dusk(w, h);
            final Bitmap s = sharp, f = frost(sharp);
            Runnable done = () -> { if (loading == index) { loading = -1; cur = p; swap(s, f); } };
            if (sync) done.run(); else main.post(done);
        };
        if (sync) work.run(); else new Thread(work, "bare-wallpaper").start();
    }

    /** 0 = the home row is in front, 1 = the grid is: the photo frosts over and darkens. */
    void setDepth(float d) {
        depth = d;
        for (Pane p : panes) p.setDepth(d);
    }

    /** 0 = apps showing, 1 = apps tucked away: the scrim lifts and the photo has the screen. */
    void setCalm(float c) {
        calm = c;
        apply();
    }

    private void apply() {
        corner.setAlpha(1f - 0.6f * calm);
        floor.setAlpha(1f - 0.6f * calm);
    }

    private void swap(Bitmap sharp, Bitmap frost) {
        final Pane out = panes[front];
        front ^= 1;
        Pane in = panes[front];
        in.animate().cancel();
        in.set(sharp, frost, depth);
        in.bringToFront();
        corner.bringToFront();
        floor.bringToFront();
        in.setAlpha(0f);
        in.animate().alpha(1f).setDuration(out.getAlpha() > 0 ? 1400 : 700)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    out.setAlpha(0f);
                    out.clear();
                    setBackground(null);   // the photo is opaque; no need to fill under it
                })
                .start();
    }

    // ---------------------------------------------------------------- loading

    /** index.txt lines are {@code file | place | photographer}; without it, every .jpg in the folder. */
    private static List<Photo> list(AssetManager am) {
        List<Photo> out = new ArrayList<>();
        try (InputStream in = am.open("wallpapers/index.txt");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            for (String line; (line = r.readLine()) != null; ) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] f = line.split("\\|");
                out.add(new Photo(f[0].trim(), f.length > 1 ? f[1].trim() : "", f.length > 2 ? f[2].trim() : ""));
            }
        } catch (IOException noIndex) {
            try {
                String[] files = am.list("wallpapers");
                if (files != null) {
                    Arrays.sort(files);
                    for (String f : files) if (f.endsWith(".jpg")) out.add(new Photo(f, "", ""));
                }
            } catch (IOException ignored) { }
        }
        return out;
    }

    /** Decodes at the smallest size that still covers the screen, then scales to fit exactly. */
    private static Bitmap decode(AssetManager am, String path, int w, int h) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = am.open(path)) { BitmapFactory.decodeStream(in, null, o); }
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap b;
        try (InputStream in = am.open(path)) { b = BitmapFactory.decodeStream(in, null, d); }
        if (b == null) return null;
        float k = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
        if (Math.abs(k - 1f) > 0.01f) {
            Bitmap s = Bitmap.createScaledBitmap(b, Math.max(w, Math.round(b.getWidth() * k)),
                    Math.max(h, Math.round(b.getHeight() * k)), true);
            if (s != b) b.recycle();
            b = s;
        }
        return b;
    }

    /**
     * A tiny, heavily blurred and darkened copy. Stretched over the screen, it reads as frosted
     * glass; the darkening is baked in so the grid needs no extra full-screen layer.
     */
    private static Bitmap frost(Bitmap src) {
        final int w = 96, h = 54;
        Bitmap b = Bitmap.createScaledBitmap(src, w, h, true).copy(Bitmap.Config.ARGB_8888, true);
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) blur(px, w, h, 3);
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            px[i] = 0xFF000000 | (Math.round(((p >> 16) & 0xFF) * 0.58f) << 16)
                    | (Math.round(((p >> 8) & 0xFF) * 0.58f) << 8) | Math.round((p & 0xFF) * 0.58f);
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
        return b;
    }

    /** One separable box-blur pass, edges clamped. */
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

    /** The stand-in picture: a dusk sky over a dark horizon, with a warm glow where the sun went down. */
    static Bitmap dusk(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h,
                new int[] { 0xFF0C1230, 0xFF26305E, 0xFF6B5A86, 0xFFD9927A, 0xFF2A1E2C },
                new float[] { 0f, 0.38f, 0.6f, 0.74f, 1f }, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(new RadialGradient(w * 0.62f, h * 0.74f, w * 0.45f, 0x66FFC79A, 0x00FFC79A, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(new LinearGradient(0, h * 0.74f, 0, h, 0xFF1A1424, 0xFF0B0B10, Shader.TileMode.CLAMP));
        android.graphics.Path hills = new android.graphics.Path();
        hills.moveTo(0, h * 0.8f);
        hills.cubicTo(w * 0.2f, h * 0.72f, w * 0.35f, h * 0.79f, w * 0.5f, h * 0.76f);
        hills.cubicTo(w * 0.68f, h * 0.73f, w * 0.82f, h * 0.8f, w, h * 0.77f);
        hills.lineTo(w, h);
        hills.lineTo(0, h);
        hills.close();
        c.drawPath(hills, p);
        return b;
    }

    /** Shade fanning out from the top-left corner, reaching nothing at both far edges. */
    private static final class Corner extends View {
        private final Paint p = new Paint(Paint.DITHER_FLAG);

        Corner(Context c) { super(c); }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            RadialGradient g = new RadialGradient(0, 0, Math.max(1, w),
                    new int[] { 0x8C000000, 0x40000000, 0x00000000 }, new float[] { 0f, 0.45f, 1f }, Shader.TileMode.CLAMP);
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(1f, h / (float) Math.max(1, w));   // a circle squashed into the box: an ellipse
            g.setLocalMatrix(m);
            p.setShader(g);
        }

        @Override
        protected void onDraw(Canvas c) { c.drawRect(0, 0, getWidth(), getHeight(), p); }
    }

    /** One photo and its frosted copy, stacked; depth fades the frost in. */
    private static final class Pane extends FrameLayout {
        final ImageView sharp, frost;

        Pane(Context c) {
            super(c);
            sharp = image(c);
            frost = image(c);
            frost.setAlpha(0f);
        }

        private ImageView image(Context c) {
            ImageView v = new ImageView(c);
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            addView(v, new LayoutParams(-1, -1));
            return v;
        }

        void set(Bitmap s, Bitmap f, float depth) {
            sharp.setImageDrawable(drawable(s));
            frost.setImageDrawable(drawable(f));
            setDepth(depth);
        }

        /** Fully frosted, the sharp photo underneath is hidden rather than drawn and covered. */
        void setDepth(float d) {
            frost.setAlpha(d);
            sharp.setVisibility(d >= 1f ? INVISIBLE : VISIBLE);
        }

        @Override public boolean hasOverlappingRendering() { return false; }

        void clear() {
            sharp.setImageDrawable(null);
            frost.setImageDrawable(null);
        }

        private BitmapDrawable drawable(Bitmap b) {
            BitmapDrawable d = new BitmapDrawable(getResources(), b);
            d.setFilterBitmap(true);
            d.setDither(true);
            return d;
        }
    }
}
