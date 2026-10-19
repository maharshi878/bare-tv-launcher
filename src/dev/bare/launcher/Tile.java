package dev.bare.launcher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * A 16:9 card with a white focus ring that sits a few dp outside it, Google-TV style.
 * The tile is slightly larger than the card to leave room for the ring; neighbours
 * butt up against each other and the rings supply the gap.
 */
final class Tile extends FrameLayout {
    /** gap between card and ring + ring stroke, in dp */
    static final int PAD_DP = 7;

    interface OnFocused { void onFocused(Tile t); }

    final Entry entry;
    OnFocused onFocused;
    private final GradientDrawable ring = new GradientDrawable();

    Tile(Context c, Entry e, int cardW, int cardH) {
        super(c);
        entry = e;
        float d = c.getResources().getDisplayMetrics().density;
        int pad = Math.round(PAD_DP * d);
        setFocusable(true);
        setClickable(true);
        setClipChildren(false);

        ring.setCornerRadius(16 * d);
        ring.setStroke(Math.round(3 * d), Color.WHITE);
        ring.setColor(Color.TRANSPARENT);
        ring.setAlpha(0);
        View ringView = new View(c);
        ringView.setBackground(ring);
        addView(ringView, new LayoutParams(cardW + 2 * pad, cardH + 2 * pad));

        View card = card(c, e, cardW, cardH, 12, 1f);
        LayoutParams lp = new LayoutParams(cardW, cardH);
        lp.leftMargin = pad;
        lp.topMargin = pad;
        addView(card, lp);

        setPivotX((cardW + 2 * pad) / 2f);
        setPivotY((cardH + 2 * pad) / 2f);
    }

    static int sizeFor(int cardPx, Context c) {
        return cardPx + 2 * Math.round(PAD_DP * c.getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onFocusChanged(boolean gain, int direction, Rect prev) {
        super.onFocusChanged(gain, direction, prev);
        float s = gain ? 1.06f : 1f;
        animate().scaleX(s).scaleY(s).setDuration(140).setInterpolator(new DecelerateInterpolator()).start();
        ring.setAlpha(gain ? 255 : 0);
        invalidate();
        if (gain && onFocused != null) onFocused.onFocused(this);
    }

    // ------------------------------------------------------------------ card art

    /** The card itself: the app's TV banner when it has one, otherwise its icon on a tinted plate. */
    static View card(Context c, Entry e, int w, int h, float radiusDp, float textScale) {
        final float d = c.getResources().getDisplayMetrics().density;
        final float r = radiusDp * d;
        FrameLayout f = new FrameLayout(c);
        f.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
        });
        f.setClipToOutline(true);

        if (e.add) {
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(r);
            g.setColor(0x14FFFFFF);
            g.setStroke(Math.round(2 * d), 0x55FFFFFF, 8 * d, 6 * d);
            f.setBackground(g);
            TextView plus = text(c, "+", 38 * textScale, 0xFFE3E3E8, "sans-serif-light");
            f.addView(plus, new LayoutParams(-1, -1));
            return f;
        }

        if (e.banner != null) {
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(copy(e.banner));
            f.addView(iv, new LayoutParams(-1, -1));
            return f;
        }

        GradientDrawable plate = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] { mix(e.glow, 0xFF26272B, 0.34f), mix(e.glow, 0xFF17181B, 0.16f) });
        f.setBackground(plate);

        if (e.icon != null) {
            ImageView iv = new ImageView(c);
            iv.setImageDrawable(copy(e.icon));
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int s = Math.round(h * 0.46f);
            LayoutParams lp = new LayoutParams(s, s, Gravity.CENTER_HORIZONTAL);
            lp.topMargin = Math.round(h * 0.14f);
            f.addView(iv, lp);
        } else {
            TextView letter = text(c, e.label.isEmpty() ? "?" : e.label.substring(0, 1).toUpperCase(),
                    30 * textScale, 0xFFFFFFFF, "sans-serif-light");
            LayoutParams lp = new LayoutParams(-1, -1);
            lp.bottomMargin = Math.round(h * 0.16f);
            f.addView(letter, lp);
        }

        TextView name = text(c, e.label, 11 * textScale, 0xCCFFFFFF, "sans-serif-medium");
        name.setSingleLine();
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(Math.round(8 * d), 0, Math.round(8 * d), 0);
        LayoutParams lp = new LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.bottomMargin = Math.round(h * 0.09f);
        f.addView(name, lp);
        return f;
    }

    private static TextView text(Context c, String s, float sp, int color, String family) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    private static Drawable copy(Drawable d) {
        Drawable.ConstantState cs = d.getConstantState();
        return cs != null ? cs.newDrawable().mutate() : d;
    }

    private static int mix(int a, int b, float t) {
        return Color.rgb(
                Math.round(Color.red(a) * t + Color.red(b) * (1 - t)),
                Math.round(Color.green(a) * t + Color.green(b) * (1 - t)),
                Math.round(Color.blue(a) * t + Color.blue(b) * (1 - t)));
    }
}
