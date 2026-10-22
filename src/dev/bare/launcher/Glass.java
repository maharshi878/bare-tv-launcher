package dev.bare.launcher;

import android.graphics.drawable.GradientDrawable;

/**
 * The one material every floating surface is made of: the tray, the status pill, menus and
 * hints. Tinted glass, a little brighter at the top where the light catches it, with a
 * hairline rim. API 28 has no live blur, so the tint carries the effect.
 */
final class Glass {
    private Glass() { }

    /** For surfaces over the picture: see-through. */
    static GradientDrawable clear(float radius, int rim) {
        return make(radius, rim, new int[] { 0x7A46464E, 0x5E2A2A30, 0x59222228 });
    }

    /** For menus and messages, which must stay readable over anything. */
    static GradientDrawable solid(float radius, int rim) {
        return make(radius, rim, new int[] { 0xF2383840, 0xF0242429, 0xF0202024 });
    }

    private static GradientDrawable make(float radius, int rim, int[] colors) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors);
        g.setCornerRadius(radius);
        g.setStroke(rim, 0x30FFFFFF);
        return g;
    }
}
