package dev.bare.launcher;

import android.content.Context;
import android.graphics.Typeface;

import java.util.HashMap;
import java.util.Map;

/**
 * The launcher's three weights. build.sh copies them into assets/fonts from the build
 * machine's installed fonts (they are not in the repo); when they are missing, the system
 * font stands in. Callers name a weight the Android way: "sans-serif" for body text,
 * "sans-serif-medium" for labels, "sans-serif-black" for titles.
 */
final class Fonts {
    private Fonts() { }

    private static final Map<String, Typeface> CACHE = new HashMap<>();

    static Typeface get(Context c, String family) {
        Typeface t = CACHE.get(family);
        if (t != null) return t;
        String file = family.equals("sans-serif-black") ? "bold"
                : family.equals("sans-serif-medium") ? "medium" : "regular";
        try {
            t = Typeface.createFromAsset(c.getAssets(), "fonts/" + file + ".otf");
        } catch (RuntimeException missing) {
            t = Typeface.create(family, Typeface.NORMAL);
        }
        CACHE.put(family, t);
        return t;
    }

    /** For drawing code with no Context: the weight if already loaded, else the system font. */
    static Typeface peek(String family) {
        Typeface t = CACHE.get(family);
        return t != null ? t : Typeface.create(family, Typeface.NORMAL);
    }
}
