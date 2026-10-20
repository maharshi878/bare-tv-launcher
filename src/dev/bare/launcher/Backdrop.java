package dev.bare.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * The room the tiles sit in: near-black, lit by a huge, soft blur of whatever is focused,
 * the way light from a screen spills onto a wall. Two stacked image layers crossfade by
 * view alpha, so the GPU only composites; nothing is redrawn per frame.
 */
final class Backdrop extends FrameLayout {
    static final int BASE = 0xFF0B0B0E;
    private static final int CLEAR = BASE & 0xFFFFFF;

    private final ImageView[] layers = new ImageView[2];
    private final FrameLayout lights;
    private int front;
    private Bitmap cur;

    Backdrop(Context c) {
        super(c);
        setBackgroundColor(BASE);
        lights = new FrameLayout(c);
        addView(lights, new LayoutParams(-1, -1));
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(1.15f);
        for (int i = 0; i < 2; i++) {
            ImageView v = new ImageView(c);
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setColorFilter(new ColorMatrixColorFilter(m));
            v.setAlpha(0f);
            lights.addView(v, new LayoutParams(-1, -1));
            layers[i] = v;
        }
        // Darken the top for the clock; fade the bottom back into the room.
        View scrim = new View(c);
        scrim.setBackground(new LayerDrawable(new Drawable[] {
                new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[] { 0xA6000000, 0x00000000, 0x00000000 }),
                new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[] { CLEAR, CLEAR, 0xF2000000 | CLEAR }) }));
        addView(scrim, new LayoutParams(-1, -1));
        setDepth(0f);
    }

    void show(Bitmap b) {
        if (b == cur) return;
        cur = b;
        final ImageView out = layers[front];
        front ^= 1;
        ImageView in = layers[front];
        in.animate().cancel();
        BitmapDrawable d = new BitmapDrawable(getResources(), b);
        d.setFilterBitmap(true);
        d.setDither(true);
        in.setImageDrawable(d);
        in.bringToFront();
        in.setAlpha(0f);
        in.animate().alpha(1f).setDuration(out.getAlpha() > 0 ? 650 : 900)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> out.setAlpha(0f)).start();
    }

    /** 0 = home position, 1 = scrolled into the grid. */
    void setDepth(float d) {
        lights.setAlpha(0.58f - 0.3f * d);
    }
}
