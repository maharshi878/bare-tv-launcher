package dev.bare.launcher;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;

/** One tile on the home screen: an installed app, a custom shortcut, or one of the built-ins. */
final class Entry {
    static final int APP = 0, CUSTOM = 1, INPUTS = 2, SETTINGS = 3, ADD = 4;

    int kind;
    String key, label, pkg, target, customId;
    Intent launch;
    Drawable icon;      // square app icon, may be null
    Drawable banner;    // 16:9 TV banner, may be null
    boolean system;
    long stamp;         // package lastUpdateTime, for the art cache

    // Derived from the artwork on first use, then kept with the entry.
    int glow;           // dominant color: tints icon plates
    Bitmap ambient;     // tiny blurred render: the backdrop behind the grid

    boolean movable() { return kind != ADD; }
}
