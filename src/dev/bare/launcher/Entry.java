package dev.bare.launcher;

import android.content.Intent;
import android.graphics.drawable.Drawable;

/** One thing on the home screen: an installed app, a custom shortcut, or the "add" tile. */
final class Entry {
    String key, label, pkg, target, customId;
    Intent launch;
    Drawable icon;      // square app icon, may be null
    Drawable banner;    // 16:9 TV banner, may be null
    int glow;           // dominant color, used to tint plates and the hero wash
    boolean system, custom, add;
}
