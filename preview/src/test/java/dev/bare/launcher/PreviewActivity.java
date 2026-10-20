package dev.bare.launcher;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/**
 * The real launcher, fed a plausible living-room TV instead of the package manager.
 * The artwork is drawn here, roughly in each app's colours, so the screenshots show the
 * three kinds of card art the launcher handles: TV banners, adaptive icons, plain icons.
 */
public class PreviewActivity extends MainActivity {
    static boolean empty, offline;

    @Override
    protected List<Entry> discoverApps() {
        List<Entry> out = new ArrayList<>();
        if (empty) return out;
        out.add(banner("com.netflix.ninja", "Netflix", 0xFF000000, 0xFF000000, "NETFLIX", 0xFFE50914, true, 0.30f));
        out.add(banner("com.google.android.youtube.tv", "YouTube", 0xFF0F0F0F, 0xFF212121, "YouTube", 0xFFFFFFFF, false, 0.22f));
        out.add(banner("com.amazon.amazonvideo.livingroom", "Prime Video", 0xFF00050D, 0xFF0F2A44, "prime video", 0xFFFFFFFF, false, 0.2f));
        out.add(banner("in.startv.hotstar", "JioHotstar", 0xFF0B1B4D, 0xFF1F3A9E, "JioHotstar", 0xFFFFFFFF, true, 0.18f));
        out.add(banner("com.disney.disneyplus", "Disney+", 0xFF0B1131, 0xFF1A3A7A, "Disney+", 0xFFFFFFFF, false, 0.22f));
        out.add(banner("com.plexapp.android", "Plex", 0xFF1F2326, 0xFF2B3036, "plex", 0xFFE5A00D, true, 0.3f));
        out.add(banner("org.xbmc.kodi", "Kodi", 0xFF12A5DA, 0xFF0B6E99, "KODI", 0xFFFFFFFF, true, 0.26f));
        out.add(banner("com.spotify.tv.android", "Spotify", 0xFF121212, 0xFF1A1A1A, "Spotify", 0xFF1ED760, true, 0.22f));
        out.add(banner("tv.twitch.android.app", "Twitch", 0xFF9146FF, 0xFF6A2BD9, "twitch", 0xFFFFFFFF, true, 0.26f));
        out.add(jellyfin());
        out.add(icon("org.videolan.vlc", "VLC", vlc()));
        out.add(icon("com.mxtech.videoplayer.ad", "MX Player", circleIcon(0xFF1E88E5, true)));
        out.add(icon("com.android.documentsui", "Files", folder()));
        out.add(icon("com.android.chrome", "Chrome", circleIcon(0xFF4285F4, false)));
        return out;
    }

    @Override
    protected List<Input> discoverInputs() {
        List<Input> out = new ArrayList<>();
        if (empty) return out;
        out.add(new Input("HDMI 1 · PlayStation", new Intent(Intent.ACTION_VIEW, Uri.parse("content://hdmi1"))));
        out.add(new Input("HDMI 2", new Intent(Intent.ACTION_VIEW, Uri.parse("content://hdmi2"))));
        out.add(new Input("AV", new Intent(Intent.ACTION_VIEW, Uri.parse("content://av"))));
        return out;
    }

    @Override
    protected boolean isOnline() { return !offline; }

    // ---------------------------------------------------------------- fake art

    private Entry base(String pkg, String label) {
        Entry e = new Entry();
        e.kind = Entry.APP;
        e.key = e.pkg = pkg;
        e.label = label;
        e.launch = new Intent(Intent.ACTION_MAIN).setPackage(pkg);
        return e;
    }

    private Entry banner(String pkg, String label, int bg1, int bg2, String mark, int ink, boolean bold, float size) {
        Entry e = base(pkg, label);
        Bitmap b = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 640, 360, bg1, bg2, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 640, 360, p);
        p.setShader(null);
        if (label.equals("YouTube")) {
            p.setColor(0xFFFF0033);
            c.drawRoundRect(new RectF(150, 140, 240, 204), 18, 18, p);
            p.setColor(0xFFFFFFFF);
            Path t = new Path(); t.moveTo(184, 155); t.lineTo(212, 172); t.lineTo(184, 189); t.close();
            c.drawPath(t, p);
        }
        p.setColor(ink);
        p.setTextSize(360 * size);
        p.setTypeface(Typeface.create(bold ? "sans-serif-condensed" : "sans-serif-medium", Typeface.BOLD));
        p.setTextAlign(Paint.Align.CENTER);
        float x = label.equals("YouTube") ? 360 : 320;
        c.drawText(mark, x, 180 - (p.ascent() + p.descent()) / 2, p);
        if (label.equals("Prime Video")) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(7);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(0xFF00A8E1);
            Path s = new Path(); s.moveTo(215, 222); s.quadTo(320, 262, 425, 214);
            c.drawPath(s, p);
        }
        e.banner = new BitmapDrawable(getResources(), b);
        e.icon = e.banner;
        return e;
    }

    private Entry icon(String pkg, String label, Drawable d) {
        Entry e = base(pkg, label);
        e.icon = d;
        e.system = pkg.startsWith("com.android");
        return e;
    }

    private Entry jellyfin() {
        Entry e = base("org.jellyfin.androidtv", "Jellyfin");
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { 0xFFAA5CC3, 0xFF00A4DC });
        Bitmap f = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(f);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        Path tri = new Path();
        tri.moveTo(216, 140); tri.lineTo(300, 290); tri.lineTo(132, 290); tri.close();
        Path hole = new Path();
        hole.moveTo(216, 196); hole.lineTo(256, 266); hole.lineTo(176, 266); hole.close();
        tri.op(hole, Path.Op.DIFFERENCE);
        c.drawPath(tri, p);
        e.icon = new AdaptiveIconDrawable(bg, new BitmapDrawable(getResources(), f));
        return e;
    }

    private Drawable vlc() {
        Bitmap b = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFF8800);
        Path cone = new Path();
        cone.moveTo(96, 18); cone.lineTo(150, 160); cone.lineTo(42, 160); cone.close();
        c.drawPath(cone, p);
        p.setColor(0xFFFFFFFF);
        c.drawRect(70, 70, 122, 84, p);
        c.drawRect(58, 110, 134, 124, p);
        p.setColor(0xFFE07000);
        c.drawRoundRect(new RectF(28, 150, 164, 176), 8, 8, p);
        return new BitmapDrawable(getResources(), b);
    }

    private Drawable circleIcon(int color, boolean play) {
        Bitmap b = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (play) {
            p.setColor(color);
            c.drawCircle(96, 96, 84, p);
            p.setColor(0xFFFFFFFF);
            Path t = new Path(); t.moveTo(78, 60); t.lineTo(134, 96); t.lineTo(78, 132); t.close();
            c.drawPath(t, p);
        } else {
            int[] cols = { 0xFFDB4437, 0xFFF4B400, 0xFF0F9D58 };
            RectF r = new RectF(12, 12, 180, 180);
            for (int i = 0; i < 3; i++) { p.setColor(cols[i]); c.drawArc(r, -90 + i * 120, 120, true, p); }
            p.setColor(0xFFFFFFFF);
            c.drawCircle(96, 96, 40, p);
            p.setColor(color);
            c.drawCircle(96, 96, 32, p);
        }
        return new BitmapDrawable(getResources(), b);
    }

    private Drawable folder() {
        Bitmap b = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF1A73E8);
        c.drawRoundRect(new RectF(20, 40, 100, 80), 12, 12, p);
        p.setColor(0xFF4C8DF6);
        c.drawRoundRect(new RectF(20, 58, 172, 160), 14, 14, p);
        return new BitmapDrawable(getResources(), b);
    }

    @SuppressWarnings("unused")
    private static Drawable solid(int c) { return new ColorDrawable(c); }
}
