package dev.bare.launcher;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/**
 * One app on the grid. At rest it is flat and slightly dimmed. Focused, it lifts towards
 * you: it grows, casts a shadow, catches a sheen across its top and a single glint, and its
 * name underneath brightens. While the apps are tucked away ("quiet"), names hide and nothing lifts.
 *
 * Built for a weak TV GPU: the shadow is a bitmap baked once per card size (not a live
 * elevation shadow), and nothing that fades has overlapping content, so no fade ever needs an
 * off-screen pass.
 */
final class Tile extends FrameLayout {
    interface Host { void onTileFocused(Tile t); }

    static final float LIFT = 1.1f;
    static final TimeInterpolator EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);

    Entry entry;
    Host host;
    final int cardW, cardH;
    final FrameLayout lift;
    private final ArtView art;
    private final View shade, sheen, glint, veil;
    private final ImageView shadow;
    private final TextView label;
    private final float d;
    private final boolean rich;
    private boolean held, quiet, recede;
    private static final Map<String, Bitmap> SHADOWS = new HashMap<>();
    private ValueAnimator wobble;

    Tile(Context c, Entry e, int cardW, int cardH, int labelSpace, boolean rich) {
        super(c);
        this.cardW = cardW;
        this.cardH = cardH;
        this.rich = rich;
        d = c.getResources().getDisplayMetrics().density;
        setFocusable(true);
        setClickable(true);
        setClipChildren(false);
        setClipToPadding(false);
        if (Build.VERSION.SDK_INT >= 26) setDefaultFocusHighlightEnabled(false);

        final float r = Math.max(8 * d, cardW * 0.072f);

        // The lifted card's shadow, baked once per card size and faded in on focus.
        final int sb = Math.round(22 * d);
        shadow = new ImageView(c);
        shadow.setImageBitmap(shadowFor(cardW, cardH, r, sb, d));
        shadow.setAlpha(0f);
        LayoutParams sl = new LayoutParams(cardW + 2 * sb, cardH + 2 * sb);
        sl.leftMargin = -sb;
        sl.topMargin = -sb;
        shadow.setPivotX(cardW / 2f + sb);
        shadow.setPivotY(cardH / 2f + sb);
        addView(shadow, sl);

        lift = new FrameLayout(c);
        lift.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
        });
        lift.setClipToOutline(true);
        lift.setPivotX(cardW / 2f);
        lift.setPivotY(cardH / 2f);
        addView(lift, new LayoutParams(cardW, cardH));

        art = new ArtView(c);
        lift.addView(art, new LayoutParams(-1, -1));

        shade = new View(c);
        shade.setBackgroundColor(0x2E000000);
        lift.addView(shade, new LayoutParams(-1, -1));

        // Light from above: a soft wash over the top half, only while lifted.
        sheen = new View(c);
        sheen.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { 0x33FFFFFF, 0x0DFFFFFF, 0x00FFFFFF }));
        sheen.setAlpha(0f);
        lift.addView(sheen, new LayoutParams(-1, -1));

        // A narrow specular band that crosses the card once when it is focused.
        glint = new View(c);
        glint.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] { 0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF }));
        glint.setRotation(16f);
        glint.setTranslationX(-cardW);
        LayoutParams gl = new LayoutParams(Math.round(cardW * 0.38f), cardH * 2, Gravity.CENTER_VERTICAL);
        lift.addView(glint, gl);

        // Hairline rim, like the edge of a pane of glass.
        View rim = new View(c);
        GradientDrawable rd = new GradientDrawable();
        rd.setCornerRadius(r);
        rd.setStroke(Math.max(1, Math.round(d)), 0x1AFFFFFF);
        rim.setBackground(rd);
        lift.addView(rim, new LayoutParams(-1, -1));

        // Steps the card back while a menu is open for another one. (Fading the whole tile
        // instead would cost an off-screen pass per tile, every frame.)
        veil = new View(c);
        veil.setBackgroundColor(0xFF000000);
        veil.setAlpha(0f);
        lift.addView(veil, new LayoutParams(-1, -1));

        label = new Flat(c);
        label.setTextSize(14);
        label.setTextColor(0xFFFFFFFF);
        label.setTypeface(Fonts.get(c, "sans-serif-medium"));
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setSingleLine();
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setShadowLayer(6 * d, 0, d, 0x99000000);
        label.setAlpha(0.6f);
        label.setTranslationY(-3 * d);
        int extra = Math.round(cardW * 0.08f);
        LayoutParams ll = new LayoutParams(cardW + 2 * extra, -2);
        ll.leftMargin = -extra;
        ll.topMargin = cardH + Math.round(cardH * (LIFT - 1f) / 2f) + Math.round(8 * d);
        addView(label, ll);

        setLayoutParams(new LayoutParams(cardW, cardH + labelSpace));
        bind(e);
    }

    void bind(Entry e) {
        entry = e;
        label.setText(e.kind == Entry.ADD ? "Add" : e.label);
        art.invalidate();
    }

    /** Keeps the tile lifted while a menu or move is in progress for it. */
    void setHeld(boolean h) {
        if (held == h) return;
        held = h;
        render();
    }

    boolean isLit() { return isFocused() || held; }

    /** Quiet: the apps are tucked under the picture. No name, no lift. */
    void setQuiet(boolean q) {
        if (quiet == q) return;
        quiet = q;
        render();
    }

    @Override
    protected void onFocusChanged(boolean gain, int direction, Rect prev) {
        super.onFocusChanged(gain, direction, prev);
        render();
        if (gain) {
            if (rich) sweep();
            if (host != null) host.onTileFocused(this);
        }
    }

    private void render() {
        boolean on = isLit() && !quiet;
        float s = on ? LIFT : 1f;
        lift.animate().scaleX(s).scaleY(s).translationX(0).translationY(0)
                .setDuration(on ? 280 : 220).setInterpolator(EASE).start();
        shadow.animate().scaleX(s).scaleY(s).alpha(on ? 1f : 0f)
                .setDuration(on ? 280 : 160).setInterpolator(EASE).start();
        shade.animate().alpha(on ? 0f : 1f).setDuration(220).start();
        sheen.animate().alpha(on ? 1f : 0f).setDuration(on ? 320 : 160).start();
        label.animate().alpha(quiet ? 0f : recede ? 0.25f : on ? 1f : 0.6f).translationY(on ? 0 : -3 * d)
                .setDuration(on ? 260 : 160).setInterpolator(EASE).start();
    }

    private void sweep() {
        glint.animate().cancel();
        glint.setTranslationX(-cardW * 0.75f);
        glint.animate().translationX(cardW * 1.1f).setStartDelay(120).setDuration(950)
                .setInterpolator(EASE).start();
    }

    /** A click: the card dips for a moment under your thumb. */
    @Override
    public void setPressed(boolean p) {
        boolean was = isPressed();
        super.setPressed(p);
        if (was == p || !isLit()) return;
        float s = p ? LIFT * 0.955f : LIFT;
        lift.animate().scaleX(s).scaleY(s).setDuration(p ? 90 : 240).setInterpolator(EASE).start();
    }

    /** Focus arrives from direction (dx, dy): start a few dp back along it, then settle. */
    void arrive(int dx, int dy) {
        lift.setTranslationX(-dx * 5 * d);
        lift.setTranslationY(-dy * 5 * d);
    }

    /** Pushed against an edge: lean that way and spring back. */
    void bump(final int dx, final int dy) {
        lift.animate().translationX(dx * 7 * d).translationY(dy * 7 * d).setDuration(90)
                .setInterpolator(EASE).withEndAction(() -> lift.animate().translationX(0).translationY(0)
                        .setDuration(380).setInterpolator(new OvershootInterpolator(2.5f)).start())
                .start();
    }

    /** The jiggle that says "I'm being moved". */
    void setWobble(boolean on) {
        if (wobble != null) { wobble.cancel(); wobble = null; }
        if (on) {
            wobble = ValueAnimator.ofFloat(-1.4f, 1.4f);
            wobble.setDuration(150);
            wobble.setRepeatCount(ValueAnimator.INFINITE);
            wobble.setRepeatMode(ValueAnimator.REVERSE);
            wobble.setInterpolator(new LinearInterpolator());
            wobble.addUpdateListener(a -> lift.setRotation((float) a.getAnimatedValue()));
            wobble.start();
        } else {
            lift.animate().rotation(0).setDuration(120).start();
        }
    }

    /** Steps the tile back while something else has the stage. */
    void setRecede(boolean r) {
        if (recede == r) return;
        recede = r;
        veil.animate().alpha(r ? 0.6f : 0f).setDuration(200).start();
        render();
    }

    /** A soft shadow under a rounded card, drawn once in software for each card size. */
    private static Bitmap shadowFor(int w, int h, float r, int border, float d) {
        String k = w + "x" + h;
        Bitmap b = SHADOWS.get(k);
        if (b != null) return b;
        b = Bitmap.createBitmap(w + 2 * border, h + 2 * border, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF000000);
        p.setShadowLayer(11 * d, 0, 6 * d, 0xA6000000);
        c.drawRoundRect(new RectF(border, border, border + w, border + h), r, r, p);
        SHADOWS.put(k, b);
        return b;
    }

    /** Text whose fades skip the off-screen pass Android otherwise uses for text with a shadow. */
    static class Flat extends TextView {
        Flat(Context c) { super(c); }
        @Override public boolean hasOverlappingRendering() { return false; }
    }

    private final class ArtView extends View {
        ArtView(Context c) { super(c); }
        @Override protected void onDraw(Canvas c) { Art.draw(c, entry, getWidth(), getHeight()); }
    }
}
