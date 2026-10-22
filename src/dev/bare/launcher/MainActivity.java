package dev.bare.launcher;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URISyntaxException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Bare: one calm grid of the apps you chose, in the order you chose, over a photograph.
 * The first row is a glass dock under a large clock; the rest of the grid lies below it.
 * Up from the dock (or a minute of nothing) tucks the apps down to the bottom edge and lets
 * the picture have the screen; any key brings them back. Hold OK on anything for options.
 * Nothing is recommended to you, nothing runs in the background.
 */
public class MainActivity extends Activity implements Tile.Host, Sheet.Listener {

    static final String ACTION_ADD = "dev.bare.launcher.ADD";

    private static final String PREFS = "bare";
    private static final String K_HIDDEN = "hidden";   // string set of entry keys
    private static final String K_ORDER = "order";     // JSON array of entry keys, as arranged
    private static final String K_PINNED = "pinned";   // v2 favourites; read once to seed the order
    private static final String K_CUSTOM = "custom";   // JSON array of {id,label,target}
    private static final String K_LAUNCHES = "launches";
    private static final String K_SOUNDS = "sounds";     // v3 on/off; read once to seed K_VOLUME
    private static final String K_VOLUME = "volume";     // Sounds.OFF / SOFT / LOUD
    private static final String K_AUTOHIDE = "autohide"; // tuck the apps away when idle
    private static final String K_ROTATE = "rotate";     // index into ROTATE_MS
    private static final String K_PHOTO = "photo";       // index of the picture showing
    private static final String K_PHOTO_AT = "photoAt";  // when it was put up, System.currentTimeMillis()

    private static final long IDLE_MS = 60_000;
    private static final long[] ROTATE_MS = { 15 * 60_000L, 60 * 60_000L, 24 * 60 * 60_000L };
    private static final String[] ROTATE_NAMES = { "Every 15 min", "Hourly", "Daily" };
    private static final String STOCK_HOME = "com.google.android.tvlauncher";

    static final String KEY_INPUTS = "@inputs", KEY_SETTINGS = "@settings", KEY_ADD = "+";
    static final int COLS = 6;

    /** A TV input (HDMI, AV) as the Inputs tile lists it. */
    static final class Input {
        final String label;
        final Intent intent;
        Input(String label, Intent intent) { this.label = label; this.intent = intent; }
    }

    private SharedPreferences prefs;
    private final Map<String, Entry> artCache = new HashMap<>();
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Tile> tiles = new LinkedHashMap<>();
    private List<Input> inputs = new ArrayList<>();

    private FrameLayout root, content;
    private Wallpaper wallpaper;
    private Tray tray;
    private LinearLayout clock, status, offline;
    private TextView caption, toast, moveHint;
    private Sheet sheet;
    private Sounds sounds;

    private int screenW, screenH, margin, pad, gap, cardW, cardH, labelSpace, labelBand, pitchY, gridTop, rowGap, topInset;
    private boolean rich, deep, calm;
    private float scroll, calmY, calmScale;
    private String focusKey;
    private Tile moving, menuFor;
    private final Runnable hideToast = () -> toast.animate().alpha(0f).translationY(dp(12)).setDuration(220).start();
    private final Runnable goCalm = () -> setCalm(true, true);
    private final Runnable rotate = this::rotateIfDue;

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            Uri u = i.getData();
            if (u != null) {
                String prefix = u.getSchemeSpecificPart() + "/";
                for (java.util.Iterator<String> it = artCache.keySet().iterator(); it.hasNext(); ) {
                    if (it.next().startsWith(prefix)) it.remove();
                }
            }
            reload();
        }
    };
    private final BroadcastReceiver netReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateNet(); }
    };

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        // The light sweep across a focused card is for TVs with GPU to spare. This class of
        // TV (1 GB, Mali-450) isn't flagged low-RAM, so judge by memory as well.
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        if (am != null) am.getMemoryInfo(mi);
        rich = am == null || (!am.isLowRamDevice() && mi.totalMem > 1536L * 1024 * 1024);
        getWindow().setBackgroundDrawable(null);   // the wallpaper covers the window
        sounds = new Sounds(this, prefs.getInt(K_VOLUME, prefs.getBoolean(K_SOUNDS, true) ? Sounds.LOUD : Sounds.OFF));
        measure();
        setContentView(buildUi());
        wallpaper.show(prefs.getInt(K_PHOTO, 0));
        handleAdd(getIntent());

        int launches = prefs.getInt(K_LAUNCHES, 0);
        prefs.edit().putInt(K_LAUNCHES, launches + 1).apply();
        if (launches < 3) root.postDelayed(() -> say("Press Up for just the picture  ·  Hold OK for options"), 1400);
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(pkgReceiver, pf);
        registerReceiver(netReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        updateNet();
        reload();
        rotateIfDue();
        poke();
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(pkgReceiver);
        unregisterReceiver(netReceiver);
        root.removeCallbacks(rotate);
        root.removeCallbacks(goCalm);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (handleAdd(intent)) return;
        // Home pressed while already home: everything closes, back to the first tile.
        if (sheet.isOpen()) sheet.close(null);
        if (moving != null) endMove();
        setCalm(false, true);
        if (!entries.isEmpty()) focus(tiles.get(entries.get(0).key), 0, 0);
        poke();
    }

    @Override
    public void onBackPressed() {
        if (moving != null) endMove();
        // Otherwise Back does nothing on the home screen.
    }

    /** `adb shell am start -a dev.bare.launcher.ADD --es name X --es target Y` */
    private boolean handleAdd(Intent i) {
        if (i == null || !ACTION_ADD.equals(i.getAction())) return false;
        String name = i.getStringExtra("name"), target = i.getStringExtra("target");
        if (target == null || target.trim().isEmpty()) return true;
        if (resolve(target) == null) {
            say("Couldn't find " + target);
            return true;
        }
        addCustom(name == null || name.trim().isEmpty() ? target : name, target);
        say("Added " + (name == null || name.trim().isEmpty() ? target : name.trim()));
        return true;
    }

    // ------------------------------------------------------------ layout

    /**
     * Everything is sized from the screen, so 1280x720, 1366x768 and 1920x1080 look the same.
     * The dock sits in the lower third with the names under its apps; the second row's top
     * edge peeks in at the bottom, the way tvOS hints there is more below. Tucked away, the
     * dock shrinks a little and settles just above the bottom edge.
     */
    private void measure() {
        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;
        margin = Math.round(screenW * 0.045f);
        pad = Math.round(screenW * 0.011f);
        gap = Math.round(screenW * 0.017f);
        cardW = (screenW - 2 * margin - 2 * pad - (COLS - 1) * gap) / COLS;
        cardH = Math.round(cardW * 0.6f);   // 5:3, the tvOS icon shape
        labelSpace = Math.round(cardH * 0.62f);
        labelBand = Math.round(cardH * 0.38f);   // room for the names inside the dock
        pitchY = cardH + labelSpace;
        gridTop = Math.round(screenH * 0.66f);
        rowGap = Math.round(screenH * 0.05f);
        topInset = Math.round(screenH * 0.1f);
        calmScale = 0.86f;
        // Scaled about the dock's top edge, the compact dock's bottom lands near the screen's.
        calmY = screenH * 0.965f - calmScale * (cardH + 2 * pad) - (gridTop - pad);
    }

    private View buildUi() {
        root = new FrameLayout(this);
        wallpaper = new Wallpaper(this);
        root.addView(wallpaper, new FrameLayout.LayoutParams(-1, -1));

        clock = buildClock();
        root.addView(clock);

        content = new FrameLayout(this);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPivotX(screenW / 2f);
        content.setPivotY(gridTop - pad);
        root.addView(content, new FrameLayout.LayoutParams(-1, screenH));

        // The glass dock the top row sits in; its corners follow the cards' with the padding added.
        tray = new Tray(this, Glass.clear(cardW * 0.072f + pad, Math.max(1, dp(1))),
                cardH + 2 * pad, cardH + labelBand + 2 * pad);
        tray.setLayoutParams(new FrameLayout.LayoutParams(screenW - 2 * margin, cardH + labelBand + 2 * pad));
        tray.setTranslationX(margin);
        tray.setTranslationY(gridTop - pad);
        content.addView(tray);

        status = buildStatus();
        root.addView(status);

        moveHint = pill("Move with the arrows  ·  Press OK when done");
        moveHint.setAlpha(0f);
        root.addView(moveHint, bottomCentre());

        sheet = new Sheet(this);
        sheet.setListener(this);
        root.addView(sheet, new FrameLayout.LayoutParams(-1, -1));

        toast = pill("");
        toast.setAlpha(0f);
        root.addView(toast, bottomCentre());
        return root;
    }

    /**
     * Top left, large: the time, the date under it and, while the apps are tucked away, where
     * the picture was taken. The time uses tabular digits so it doesn't shuffle as it ticks.
     */
    private LinearLayout buildClock() {
        LinearLayout box = new LinearLayout(this) {
            @Override public boolean hasOverlappingRendering() { return false; }   // fades without a layer
        };
        box.setOrientation(LinearLayout.VERTICAL);

        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("HH:mm");
        time.setTextSize(TypedValueSp.of(this, screenH * 0.135f));
        time.setTextColor(0xFFFFFFFF);
        time.setTypeface(Fonts.get(this, "sans-serif-medium"));
        time.setFontFeatureSettings("tnum");
        time.setLetterSpacing(-0.02f);
        time.setIncludeFontPadding(false);
        time.setShadowLayer(dp(10), 0, dp(1), 0x40000000);
        box.addView(time);

        TextClock date = new TextClock(this);
        String day = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM");
        date.setFormat12Hour(day);
        date.setFormat24Hour(day);
        date.setTextSize(TypedValueSp.of(this, screenH * 0.034f));
        date.setTextColor(0xE6FFFFFF);
        date.setTypeface(Fonts.get(this, "sans-serif-medium"));
        date.setIncludeFontPadding(false);
        date.setShadowLayer(dp(8), 0, dp(1), 0x40000000);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-2, -2);
        dl.topMargin = Math.round(screenH * 0.012f);
        dl.leftMargin = Math.round(screenH * 0.006f);
        box.addView(date, dl);

        caption = label("", 13, 0xB3FFFFFF, "sans-serif");   // a Tile.Flat: fades without a layer
        caption.setShadowLayer(dp(6), 0, dp(1), 0x59000000);
        caption.setAlpha(0f);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
        cl.topMargin = Math.round(screenH * 0.022f);
        cl.leftMargin = dl.leftMargin;
        box.addView(caption, cl);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
        lp.leftMargin = margin;
        lp.topMargin = Math.round(screenH * 0.075f);
        box.setLayoutParams(lp);
        return box;
    }

    /** Top right, a small glass pill that appears only when the TV is offline. */
    private LinearLayout buildStatus() {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(14), dp(7), dp(16), dp(7));
        pill.setBackground(Glass.clear(dp(20), Math.max(1, dp(1))));

        offline = pill;
        Glyph wifi = new Glyph(this, Glyph.WIFI_OFF);
        wifi.setColor(0xE6FFFFFF);
        pill.addView(wifi, new LinearLayout.LayoutParams(dp(16), dp(16)));
        TextView ot = label("Offline", 13, 0xE6FFFFFF, "sans-serif-medium");
        ot.setPadding(dp(7), 0, 0, 0);
        pill.addView(ot);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        lp.topMargin = Math.round(screenH * 0.085f);
        lp.rightMargin = margin;
        pill.setLayoutParams(lp);
        return pill;
    }

    private int slotX(int i) { return margin + pad + (i % COLS) * (cardW + gap); }
    private int slotY(int i) {
        int r = i / COLS;
        return r == 0 ? gridTop : gridTop + cardH + labelBand + pad + rowGap + (r - 1) * pitchY;
    }

    /** Places every tile in its slot, reusing tile views across reloads. */
    private void layoutTiles(boolean animate) {
        Set<String> live = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            live.add(e.key);
            Tile t = tiles.get(e.key);
            boolean fresh = t == null;
            if (fresh) {
                t = new Tile(this, e, cardW, cardH, labelSpace, rich);
                t.host = this;
                t.setOnClickListener(v -> activate((Tile) v));
                t.setOnLongClickListener(v -> { showMenu((Tile) v); return true; });
                tiles.put(e.key, t);
                content.addView(t);
            } else {
                t.bind(e);
            }
            t.setQuiet(calm);
            float x = slotX(i), y = slotY(i);
            if (animate && !fresh) {
                t.animate().translationX(x).translationY(y).setDuration(300).setInterpolator(Tile.EASE).start();
            } else {
                t.setTranslationX(x);
                t.setTranslationY(y);
            }
        }
        for (java.util.Iterator<Map.Entry<String, Tile>> it = tiles.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Tile> me = it.next();
            if (!live.contains(me.getKey())) { content.removeView(me.getValue()); it.remove(); }
        }
        int bottom = slotY(Math.max(0, entries.size() - 1)) + pitchY + screenH;
        // Tall enough for every row, so nothing below the first screen is ever culled.
        if (content.getLayoutParams().height != bottom) {
            content.getLayoutParams().height = bottom;
            content.requestLayout();
        }
    }

    // ------------------------------------------------------------ focus and navigation

    @Override
    public void onTileFocused(Tile t) {
        focusKey = t.entry.key;
        scrollTo(indexOf(focusKey) / COLS);
    }

    /** Keeps the focused row comfortably on screen. Row 0 is home: the clock and the picture are in view. */
    private void scrollTo(int row) {
        float target;
        if (row == 0) {
            target = 0;
        } else {
            target = scroll;
            float rowTop = slotY(row * COLS), rowBottom = rowTop + pitchY;
            if (scroll == 0 || rowTop + target < topInset) target = topInset - rowTop;
            if (rowBottom + target > screenH - topInset * 0.3f) target = screenH - topInset * 0.3f - rowBottom;
        }
        if (target == scroll) return;
        scroll = target;
        content.animate().translationY(target).setDuration(420).setInterpolator(Tile.EASE).start();
        boolean d = row > 0;
        if (d == deep) return;
        deep = d;
        // Into the grid, the clock drifts up and away and the picture frosts over behind the apps.
        clock.animate().alpha(d ? 0f : 1f).translationY(d ? -screenH * 0.05f : 0)
                .setDuration(d ? 260 : 460).setInterpolator(Tile.EASE).start();
        status.animate().alpha(d ? 0f : 1f).setDuration(d ? 200 : 400).start();
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(d ? 0f : 1f, d ? 1f : 0f);
        va.setDuration(d ? 420 : 520);
        va.setInterpolator(Tile.EASE);
        va.addUpdateListener(a -> wallpaper.setDepth((float) a.getAnimatedValue()));
        va.start();
    }

    // ------------------------------------------------------------ tucked away

    /**
     * Calm: the dock shrinks to the bottom edge, names and focus fade, the scrim lifts and the
     * picture's place and photographer appear under the date. Only from the home row.
     */
    private void setCalm(boolean c, boolean animate) {
        if (c && (deep || moving != null || sheet.isOpen())) return;
        root.removeCallbacks(goCalm);
        if (c == calm) return;
        calm = c;
        for (Tile t : tiles.values()) t.setQuiet(c);
        Wallpaper.Photo p = wallpaper.current();
        if (c) caption.setText(p == null || p.place.isEmpty() ? "" : p.credit.isEmpty() ? p.place : p.place + "  ·  " + p.credit);
        long ms = animate ? (c ? 900 : 420) : 0;
        content.animate().translationY(c ? calmY : scroll).scaleX(c ? calmScale : 1f).scaleY(c ? calmScale : 1f)
                .setDuration(ms).setInterpolator(Tile.EASE).start();
        caption.animate().alpha(c ? 1f : 0f).setStartDelay(c ? 450 : 0).setDuration(c ? 700 : 200).start();
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(c ? 0f : 1f, c ? 1f : 0f);
        va.setDuration(ms);
        va.setInterpolator(Tile.EASE);
        va.addUpdateListener(a -> {
            float v = (float) a.getAnimatedValue();
            wallpaper.setCalm(v);
            tray.setFill(1f - v);
        });
        va.start();
        if (animate) sounds.air();
    }

    /** Something happened: postpone tucking the apps away. */
    private void poke() {
        root.removeCallbacks(goCalm);
        if (prefs.getBoolean(K_AUTOHIDE, true) && !calm) root.postDelayed(goCalm, IDLE_MS);
    }

    // ------------------------------------------------------------ pictures

    /** Puts up the next picture when the current one has been up long enough, and books the next check. */
    private void rotateIfDue() {
        root.removeCallbacks(rotate);
        long period = ROTATE_MS[Math.min(ROTATE_MS.length - 1, prefs.getInt(K_ROTATE, 0))];
        long now = System.currentTimeMillis(), at = prefs.getLong(K_PHOTO_AT, 0);
        if (at == 0 || at > now) {
            prefs.edit().putLong(K_PHOTO_AT, now).apply();
            at = now;
        } else if (now - at >= period) {
            nextPicture();
            at = now;
        }
        root.postDelayed(rotate, Math.max(60_000, period - (now - at)));
    }

    private void nextPicture() {
        int i = (prefs.getInt(K_PHOTO, 0) + 1) % wallpaper.count();
        prefs.edit().putInt(K_PHOTO, i).putLong(K_PHOTO_AT, System.currentTimeMillis()).apply();
        wallpaper.show(i);
        if (calm) root.postDelayed(() -> {
            Wallpaper.Photo p = wallpaper.current();
            caption.setText(p == null || p.place.isEmpty() ? "" : p.credit.isEmpty() ? p.place : p.place + "  ·  " + p.credit);
        }, 800);
    }

    private int indexOf(String key) {
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).key.equals(key)) return i;
        return -1;
    }

    private void focus(Tile t, int dx, int dy) {
        if (t == null) return;
        if (dx != 0 || dy != 0) t.arrive(dx, dy);
        t.requestFocus();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        int dx = k == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        int dy = k == KeyEvent.KEYCODE_DPAD_UP ? -1 : k == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        boolean arrow = dx != 0 || dy != 0;
        boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A;
        poke();
        if (calm) {
            // Any remote key brings the apps back and does nothing else; volume and media keys pass.
            if (arrow || ok || k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_MENU) {
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) { setCalm(false, true); poke(); }
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (sheet.isOpen()) {
            View before = root.findFocus();
            boolean r = super.dispatchKeyEvent(e);
            if (arrow && e.getAction() == KeyEvent.ACTION_DOWN && root.findFocus() != before) sounds.tick();
            return r;
        }
        if (moving != null) {
            if (arrow && e.getAction() == KeyEvent.ACTION_DOWN) { shift(dx, dy); return true; }
            if (ok) { if (e.getAction() == KeyEvent.ACTION_UP) endMove(); return true; }
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_MENU) {
                if (e.getAction() == KeyEvent.ACTION_UP) endMove();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }

        View f = root.findFocus();
        if (!(f instanceof Tile)) {
            // Focus somehow left the grid: any arrow brings it back.
            if (arrow && e.getAction() == KeyEvent.ACTION_DOWN && !entries.isEmpty()) {
                Tile t = tiles.get(focusKey);
                focus(t != null ? t : tiles.get(entries.get(0).key), 0, 0);
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        Tile cur = (Tile) f;
        if (k == KeyEvent.KEYCODE_MENU) {
            if (e.getAction() == KeyEvent.ACTION_UP) showMenu(cur);
            return true;
        }
        if (!arrow) return super.dispatchKeyEvent(e);
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;

        int i = indexOf(cur.entry.key), n = entries.size();
        int j = neighbour(i, dx, dy, n);
        if (j < 0 && dy < 0 && i < COLS) { setCalm(true, true); return true; }   // up off the dock
        if (j < 0) { cur.bump(dx, dy); sounds.thud(); }
        else { focus(tiles.get(entries.get(j).key), dx, dy); sounds.tick(); }
        return true;
    }

    /** Grid neighbour of slot i, or -1 at an edge. Down from a short last row lands on its last tile. */
    private static int neighbour(int i, int dx, int dy, int n) {
        int col = i % COLS, row = i / COLS, lastRow = (n - 1) / COLS;
        if (dx < 0) return col > 0 ? i - 1 : -1;
        if (dx > 0) return col < COLS - 1 && i + 1 < n ? i + 1 : -1;
        if (dy < 0) return row > 0 ? i - COLS : -1;
        if (dy > 0) return row < lastRow ? Math.min(i + COLS, n - 1) : -1;
        return -1;
    }

    // ------------------------------------------------------------ moving tiles

    private void beginMove(Tile t) {
        moving = t;
        t.setHeld(true);
        t.setWobble(true);
        t.requestFocus();
        for (Tile o : tiles.values()) if (o != t) o.setRecede(true);
        moveHint.animate().alpha(1f).translationY(0).setDuration(240).start();
    }

    private void shift(int dx, int dy) {
        int i = indexOf(moving.entry.key), n = entries.size();
        int j = neighbour(i, dx, dy, n);
        if (j >= 0 && !entries.get(j).movable()) j = -1;
        if (j < 0) { moving.bump(dx, dy); sounds.thud(); return; }
        sounds.tick();
        Entry e = entries.remove(i);
        entries.add(j, e);
        layoutTiles(true);
        scrollTo(j / COLS);
    }

    private void endMove() {
        Tile t = moving;
        moving = null;
        t.setWobble(false);
        t.setHeld(false);
        for (Tile o : tiles.values()) o.setRecede(false);
        moveHint.animate().alpha(0f).setDuration(180).start();
        saveOrder();
        t.requestFocus();
    }

    private void saveOrder() {
        JSONArray a = new JSONArray();
        for (Entry e : entries) if (e.movable()) a.put(e.key);
        prefs.edit().putString(K_ORDER, a.toString()).apply();
    }

    // ------------------------------------------------------------ data

    private void reload() {
        Set<String> hidden = hidden();
        List<Entry> found = new ArrayList<>();
        for (Entry e : discoverApps()) if (!hidden.contains(e.key)) found.add(e);

        JSONArray cs = customs();
        for (int i = 0; i < cs.length(); i++) {
            JSONObject o = cs.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id"), target = o.optString("target"), label = o.optString("label");
            String cacheKey = "c:" + id + "/" + target + "/" + label;
            Entry e = artCache.get(cacheKey);
            if (e == null) {
                e = new Entry();
                e.kind = Entry.CUSTOM;
                e.customId = id;
                e.key = "c:" + id;
                e.label = label;
                e.target = target;
                artCache.put(cacheKey, e);
            }
            // Re-resolved every time, so installing or removing the target app is reflected.
            Resolved r = resolve(target);
            e.launch = r == null ? null : r.intent;
            if (e.icon != (r == null ? null : r.icon)) { e.icon = r == null ? null : r.icon; e.glow = 0; }
            found.add(e);
        }

        inputs = discoverInputs();
        if (!inputs.isEmpty() && !hidden.contains(KEY_INPUTS)) found.add(builtin(KEY_INPUTS, Entry.INPUTS, "Inputs"));
        if (!hidden.contains(KEY_SETTINGS)) found.add(builtin(KEY_SETTINGS, Entry.SETTINGS, "Settings"));

        entries.clear();
        entries.addAll(arrange(found));
        entries.add(builtin(KEY_ADD, Entry.ADD, "Add"));
        layoutTiles(false);

        Tile t = focusKey == null ? null : tiles.get(focusKey);
        if (t == null && !entries.isEmpty()) t = tiles.get(entries.get(0).key);
        if (t != null && moving == null && !sheet.isOpen()) {
            final Tile f = t;
            f.post(() -> { if (!f.isFocused()) f.requestFocus(); else onTileFocused(f); });
        }
    }

    private Entry builtin(String key, int kind, String label) {
        Entry e = artCache.get(key);
        if (e == null) {
            e = new Entry();
            e.key = key;
            e.kind = kind;
            e.label = label;
            e.system = true;
            artCache.put(key, e);
        }
        return e;
    }

    /**
     * Saved order first; anything new after it. With no saved order: Inputs, then apps
     * and shortcuts alphabetically, then Settings.
     */
    private List<Entry> arrange(List<Entry> found) {
        Map<String, Entry> byKey = new LinkedHashMap<>();
        for (Entry e : found) byKey.put(e.key, e);
        List<Entry> out = new ArrayList<>();
        for (String k : order()) { Entry e = byKey.remove(k); if (e != null) out.add(e); }
        List<Entry> rest = new ArrayList<>(byKey.values());
        final Collator col = Collator.getInstance();
        Collections.sort(rest, (a, b) -> {
            int ra = rank(a), rb = rank(b);
            return ra != rb ? ra - rb : col.compare(a.label, b.label);
        });
        out.addAll(rest);
        return out;
    }

    private static int rank(Entry e) { return e.kind == Entry.INPUTS ? 0 : e.kind == Entry.SETTINGS ? 2 : 1; }

    /** Every launchable app, Leanback activity preferred. Overridden by the screenshot harness. */
    protected List<Entry> discoverApps() {
        PackageManager pm = getPackageManager();
        Map<String, Entry> byPkg = new LinkedHashMap<>();
        for (String cat : new String[] { Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER }) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || byPkg.containsKey(pkg)) continue;
                // The built-in Settings tile stands in for these.
                if (pkg.equals("com.android.tv.settings") || pkg.equals("com.android.settings")) continue;
                long stamp = 0;
                try { stamp = pm.getPackageInfo(pkg, 0).lastUpdateTime; } catch (PackageManager.NameNotFoundException ignored) { }
                String cacheKey = pkg + "/" + ri.activityInfo.name;
                Entry e = artCache.get(cacheKey);
                if (e == null || e.stamp != stamp) {
                    e = new Entry();
                    e.kind = Entry.APP;
                    e.key = pkg;
                    e.pkg = pkg;
                    e.stamp = stamp;
                    e.label = String.valueOf(ri.loadLabel(pm));
                    e.icon = ri.loadIcon(pm);
                    e.banner = ri.activityInfo.loadBanner(pm);
                    e.system = (ri.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    e.launch = new Intent(Intent.ACTION_MAIN).addCategory(cat)
                            .setClassName(pkg, ri.activityInfo.name)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    artCache.put(cacheKey, e);
                }
                byPkg.put(pkg, e);
            }
        }
        return new ArrayList<>(byPkg.values());
    }

    /** HDMI/AV sources from the system's TV input service. Overridden by the screenshot harness. */
    protected List<Input> discoverInputs() {
        List<Input> out = new ArrayList<>();
        try {
            TvInputManager tm = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            if (tm == null) return out;
            for (TvInputInfo info : tm.getTvInputList()) {
                if (!info.isPassthroughInput()) continue;
                CharSequence custom = info.loadCustomLabel(this);
                out.add(new Input(String.valueOf(custom != null ? custom : info.loadLabel(this)),
                        new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.getId()))));
            }
        } catch (RuntimeException ignored) { }
        return out;
    }

    /** Overridden by the screenshot harness. */
    protected boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }

    private void updateNet() {
        status.setVisibility(isOnline() ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------ prefs

    private Set<String> hidden() {
        return new HashSet<>(prefs.getStringSet(K_HIDDEN, new HashSet<String>()));
    }

    private void setHidden(Set<String> h) { prefs.edit().putStringSet(K_HIDDEN, h).apply(); }

    private List<String> order() {
        String raw = prefs.getString(K_ORDER, null);
        if (raw == null) raw = prefs.getString(K_PINNED, "[]");
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (JSONException ignored) { }
        return out;
    }

    private JSONArray customs() {
        try { return new JSONArray(prefs.getString(K_CUSTOM, "[]")); }
        catch (JSONException e) { return new JSONArray(); }
    }

    private void addCustom(String label, String target) {
        try {
            JSONArray a = customs();
            String id = String.valueOf(System.currentTimeMillis());
            a.put(new JSONObject().put("id", id).put("label", label.trim()).put("target", target.trim()));
            prefs.edit().putString(K_CUSTOM, a.toString()).apply();
            focusKey = "c:" + id;
        } catch (JSONException ignored) { }
        reload();
    }

    private void removeCustom(String id) {
        JSONArray a = customs(), out = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && !id.equals(o.optString("id"))) out.put(o);
        }
        prefs.edit().putString(K_CUSTOM, out.toString()).apply();
        forget("c:" + id);
    }

    /** Moves focus off an entry that is about to disappear, then reloads. */
    private void forget(String key) {
        int i = indexOf(key);
        if (key.equals(focusKey) && i >= 0) {
            focusKey = i + 1 < entries.size() ? entries.get(i + 1).key : i > 0 ? entries.get(i - 1).key : null;
        }
        reload();
    }

    // ------------------------------------------------------------ custom targets

    private static final class Resolved {
        Intent intent;
        android.graphics.drawable.Drawable icon;
    }

    /**
     * A target is one of: an intent: URI, a link (anything with "://"), "pkg/.Activity",
     * an installed package name, or a bare host like youtube.com.
     */
    private Resolved resolve(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        PackageManager pm = getPackageManager();
        Resolved r = new Resolved();
        try {
            if (t.startsWith("intent:")) {
                r.intent = Intent.parseUri(t, Intent.URI_INTENT_SCHEME);
            } else if (t.contains("://")) {
                r.intent = new Intent(Intent.ACTION_VIEW, Uri.parse(t));
            } else if (t.contains("/")) {
                ComponentName cn = ComponentName.unflattenFromString(t);
                if (cn == null) return null;
                r.intent = new Intent(Intent.ACTION_MAIN).setComponent(cn);
                try { r.icon = pm.getActivityIcon(cn); } catch (PackageManager.NameNotFoundException ignored) { }
            } else if (isInstalled(pm, t)) {
                Intent i = pm.getLeanbackLaunchIntentForPackage(t);
                if (i == null) i = pm.getLaunchIntentForPackage(t);
                if (i == null) return null;
                r.intent = i;
                try { r.icon = pm.getApplicationIcon(t); } catch (PackageManager.NameNotFoundException ignored) { }
            } else if (t.contains(".") && !t.contains(" ")) {
                r.intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://" + t));
            } else {
                return null;
            }
        } catch (URISyntaxException e) {
            return null;
        }
        r.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return r;
    }

    private static boolean isInstalled(PackageManager pm, String pkg) {
        try { pm.getPackageInfo(pkg, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    // ------------------------------------------------------------ actions

    private void activate(Tile t) {
        sounds.tock();
        Entry e = t.entry;
        switch (e.kind) {
            case Entry.ADD: showAddMenu(t); return;
            case Entry.INPUTS: showInputs(t); return;
            case Entry.SETTINGS: open(new Intent(Settings.ACTION_SETTINGS), t); return;
        }
        if (e.launch == null) { say(e.label + " isn't installed"); return; }
        open(e.launch, t);
    }

    /** Opens {@code i}, zooming out of the tile it came from. Never lets a bad intent crash home. */
    private void open(Intent i, Tile from) {
        Bundle opts = null;
        if (from != null) {
            opts = ActivityOptions.makeScaleUpAnimation(from.lift, 0, 0, from.cardW, from.cardH).toBundle();
        }
        try {
            startActivity(new Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts);
        } catch (ActivityNotFoundException | SecurityException ex) {
            say("Nothing on this TV can open that");
        }
    }

    private void showInputs(Tile t) {
        if (inputs.isEmpty()) { say("This TV didn't report any inputs"); return; }
        sheet.begin("Inputs", 300);
        for (final Input in : inputs) sheet.row(Glyph.INPUT, in.label, () -> open(in.intent, null));
        present(t);
    }

    // ------------------------------------------------------------ menus

    private void showMenu(final Tile t) {
        final Entry e = t.entry;
        if (e.kind == Entry.ADD) { showAddMenu(t); return; }
        sheet.begin(e.label, 300);
        sheet.row(Glyph.MOVE, "Move", () -> beginMove(t));
        if (indexOf(e.key) >= COLS) sheet.row(Glyph.TOP, "Move to top row", () -> toTopRow(e));
        if (e.kind == Entry.APP) {
            sheet.row(Glyph.INFO, "App info", () ->
                    open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg)), null));
        }
        if (e.kind == Entry.CUSTOM) {
            sheet.row(Glyph.TRASH, "Delete shortcut", () -> removeCustom(e.customId));
        } else {
            sheet.row(Glyph.HIDE, "Hide", () -> {
                Set<String> h = hidden();
                h.add(e.key);
                setHidden(h);
                say(e.label + " hidden. Add › Apps brings it back.");
                forget(e.key);
            });
        }
        if (e.kind == Entry.APP && !e.system) {
            sheet.row(Glyph.TRASH, "Uninstall", () ->
                    open(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + e.pkg)), null));
        }
        sheet.row(Glyph.SLIDERS, "Bare settings…", () -> showSettings(t));
        present(t);
    }

    private void toTopRow(Entry e) {
        entries.remove(e);
        entries.add(Math.min(COLS - 1, entries.size() - 1), e);
        layoutTiles(true);
        saveOrder();
        Tile t = tiles.get(e.key);
        if (t != null) t.post(t::requestFocus);
    }

    private void showAddMenu(final Tile t) {
        final Set<String> hidden = hidden();
        sheet.begin("Add", 360);
        sheet.row(Glyph.SHOW, hidden.isEmpty() ? "Apps…" : "Apps (" + hidden.size() + " hidden)…", () -> showApps(t));
        sheet.row(Glyph.GLOBE, "Website…", this::showWebForm);
        sheet.row(Glyph.PLUS, "Advanced shortcut…", this::showAddForm);
        sheet.row(Glyph.SLIDERS, "Bare settings…", () -> showSettings(t));
        present(t);
    }

    /** A website tile: a name and an address, nothing to know about packages or intents. */
    private void showWebForm() {
        sheet.begin("Website", 440);
        final EditText name = sheet.field("Name, like Jellyfin");
        final EditText addr = sheet.field("Address, like youtube.com/tv");
        sheet.buttons(new String[] { "Cancel", "Add" }, new Runnable[] { null, () -> {
            String a = addr.getText().toString().trim();
            if (a.isEmpty() || a.contains(" ") || !a.contains(".")) { say("Type an address, like youtube.com"); return; }
            String url = a.contains("://") ? a : "https://" + a;
            if (resolve(url) == null) { say("Nothing on this TV can open that address"); return; }
            String n = name.getText().toString().trim();
            if (n.isEmpty()) {
                String host = Uri.parse(url).getHost();
                n = host == null ? a : host.startsWith("www.") ? host.substring(4) : host;
            }
            sheet.close(null);
            addCustom(n, url);
            say("Added " + n);
        } }, new boolean[] { true, false });
        present(null);
    }

    /** Bare's own settings. Values change in place; the sheet stays open until Back. */
    private void showSettings(final Tile t) {
        sheet.begin("Bare", 400);
        int vol = sounds.level();
        sheet.option(Glyph.SOUND, "Sounds", new String[] { "Loud", "Soft", "Off" },
                vol == Sounds.LOUD ? 0 : vol == Sounds.SOFT ? 1 : 2, i -> {
                    sounds.setLevel(i == 0 ? Sounds.LOUD : i == 1 ? Sounds.SOFT : Sounds.OFF);
                    prefs.edit().putInt(K_VOLUME, sounds.level()).apply();
                    sounds.tock();
                });
        sheet.option(Glyph.DOCK, "Hide apps after a minute", new String[] { "On", "Off" },
                prefs.getBoolean(K_AUTOHIDE, true) ? 0 : 1, i -> prefs.edit().putBoolean(K_AUTOHIDE, i == 0).apply());
        sheet.option(Glyph.PHOTO, "New picture", ROTATE_NAMES,
                Math.min(ROTATE_NAMES.length - 1, prefs.getInt(K_ROTATE, 0)), i -> {
                    prefs.edit().putInt(K_ROTATE, i).apply();
                    rotateIfDue();
                });
        sheet.row(Glyph.PHOTO, "Show the next picture", this::nextPicture);
        sheet.row(Glyph.HOME, HomeKey.running ? "Home button opens Bare" : "Make Home open Bare…", this::showHomeSetup);
        if (getPackageManager().getLaunchIntentForPackage(STOCK_HOME) != null
                || getPackageManager().getLeanbackLaunchIntentForPackage(STOCK_HOME) != null) {
            sheet.row(Glyph.TV, "Open the Google TV home screen", this::openStockHome);
        }
        present(t);
    }

    /**
     * The TV's own launcher outranks every app for the Home button, so Bare offers an
     * accessibility service instead (HomeKey). It can only be turned on by the owner, in Settings.
     */
    private void showHomeSetup() {
        sheet.begin(HomeKey.running ? "Home button" : "Make Home open Bare", 460);
        if (!HomeKey.running && !hasAccessibilityScreen()) {
            // Some firmware (Xiaomi's, for one) ships Settings without an Accessibility screen.
            sheet.note("This TV's Settings has no Accessibility screen, so the switch for Bare\n"
                    + "can only be turned on from a computer, over adb:\n"
                    + "settings put secure enabled_accessibility_services " + getPackageName() + "/.HomeKey\n"
                    + "settings put secure accessibility_enabled 1");
            sheet.buttons(new String[] { "OK" }, new Runnable[] { null }, new boolean[] { true });
            present(null);
            return;
        }
        if (HomeKey.running) {
            sheet.note("Bare is answering the Home button. The Google TV home screen is still installed;\n"
                    + "to hand Home back to it, turn off Bare in Settings › Accessibility.");
        } else {
            sheet.note("In the next screen, choose Bare under Services and turn it on.\n"
                    + "Bare then opens whenever you press Home. It only watches the Home\n"
                    + "button and which home screen is showing, nothing else.");
        }
        sheet.buttons(new String[] { "Not now", "Open Settings" }, new Runnable[] { null, () ->
                open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), null) }, new boolean[] { true, true });
        present(null);
    }

    /** True when something other than the framework's empty stub answers ACCESSIBILITY_SETTINGS. */
    private boolean hasAccessibilityScreen() {
        ResolveInfo ri = getPackageManager().resolveActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), 0);
        return ri != null && ri.activityInfo != null && !ri.activityInfo.packageName.contains("frameworkpackagestubs");
    }

    private void openStockHome() {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(STOCK_HOME);
        if (i == null) i = pm.getLaunchIntentForPackage(STOCK_HOME);
        if (i == null) { say("The Google TV home screen isn't available"); return; }
        HomeKey.visitingStock = true;   // until the next Home press
        open(i, null);
    }

    private void showAddForm() {
        sheet.begin("Advanced shortcut", 440);
        final EditText name = sheet.field("Name");
        final EditText target = sheet.field("App, link, or package/.Activity");
        sheet.note("For example  org.jellyfin.androidtv,  youtube.com/tv,\nor  com.example.app/.MainActivity");
        sheet.buttons(new String[] { "Cancel", "Add" }, new Runnable[] { null, () -> {
            String tg = target.getText().toString();
            if (resolve(tg) == null) { say("Couldn't find that app or link"); return; }
            String n = name.getText().toString().trim();
            sheet.close(null);
            addCustom(n.isEmpty() ? tg.trim() : n, tg);
        } }, new boolean[] { true, false });
        present(null);
    }

    /**
     * Every app on the TV, alphabetically, ticked if it is on the home screen. Tick to add,
     * untick to hide; Done applies both. Uninstalled leftovers drop out of the hidden set here.
     */
    private void showApps(Tile t) {
        final List<String> keys = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        final List<Entry> arts = new ArrayList<>();
        List<Entry> all = discoverApps();
        final Collator col = Collator.getInstance();
        Collections.sort(all, (x, y) -> col.compare(x.label, y.label));
        for (Entry e : all) { keys.add(e.key); labels.add(e.label); arts.add(e); }
        if (!discoverInputs().isEmpty()) { keys.add(KEY_INPUTS); labels.add("Inputs"); arts.add(builtin(KEY_INPUTS, Entry.INPUTS, "Inputs")); }
        keys.add(KEY_SETTINGS); labels.add("Settings"); arts.add(builtin(KEY_SETTINGS, Entry.SETTINGS, "Settings"));

        Set<String> h = hidden();
        if (h.retainAll(keys)) setHidden(h);   // forget hidden apps that were uninstalled
        final boolean[] on = new boolean[keys.size()];
        sheet.begin("Apps on your home screen", 440);
        sheet.beginList(Math.round(screenH * 0.56f));
        for (int i = 0; i < keys.size(); i++) sheet.check(labels.get(i), !h.contains(keys.get(i)), on, i, arts.get(i));
        sheet.endList();
        sheet.buttons(new String[] { "Cancel", "Done" }, new Runnable[] { null, () -> {
            Set<String> nh = hidden();
            int added = 0, removed = 0;
            for (int i = 0; i < on.length; i++) {
                String k = keys.get(i);
                if (on[i] && nh.remove(k)) added++;
                else if (!on[i] && nh.add(k)) removed++;
            }
            setHidden(nh);
            if (focusKey != null && nh.contains(focusKey)) focusKey = null;
            reload();
            if (added + removed > 0) {
                say((added > 0 ? added + (added == 1 ? " app added" : " apps added") : "")
                        + (added > 0 && removed > 0 ? ", " : "")
                        + (removed > 0 ? removed + " hidden" : ""));
            }
        } }, new boolean[] { true, true });
        present(t);
    }

    /** Opens the sheet beside {@code t} (or centred) with the rest of the grid stepped back. */
    private void present(Tile t) {
        root.removeCallbacks(goCalm);
        menuFor = t;
        content.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        for (Tile o : tiles.values()) o.setRecede(o != t);
        if (t != null) t.setHeld(true);
        sounds.pop();
        sheet.show(t == null ? null : cardRect(t));
    }

    @Override
    public void onSheetClosed() {
        content.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        Tile back = menuFor != null && menuFor.getParent() != null ? menuFor : tiles.get(focusKey);
        menuFor = null;
        for (Tile o : tiles.values()) { o.setRecede(false); if (o != moving) o.setHeld(false); }
        if (back == null && !entries.isEmpty()) back = tiles.get(entries.get(0).key);
        if (back != null) back.requestFocus();
        poke();
    }

    /** Where the lifted card is on screen, for placing a sheet beside it. */
    private Rect cardRect(Tile t) {
        int grow = Math.round(t.cardW * (Tile.LIFT - 1f) / 2f), growY = Math.round(t.cardH * (Tile.LIFT - 1f) / 2f);
        int x = Math.round(t.getTranslationX()), y = Math.round(t.getTranslationY() + scroll);
        return new Rect(x - grow, y - growY, x + t.cardW + grow, y + t.cardH + growY);
    }

    // ------------------------------------------------------------ small things

    /** A quiet message at the bottom of the screen. */
    void say(String msg) {
        toast.setText(msg);
        toast.setTranslationY(dp(12));
        toast.animate().alpha(1f).translationY(0).setDuration(240).setInterpolator(Tile.EASE).start();
        root.removeCallbacks(hideToast);
        root.postDelayed(hideToast, 3200);
    }

    private TextView pill(String s) {
        TextView t = label(s, 15, 0xFFFFFFFF, "sans-serif-medium");
        t.setPadding(dp(22), dp(12), dp(22), dp(12));
        t.setBackground(Glass.solid(dp(24), Math.max(1, dp(1))));
        t.setElevation(dp(12));
        return t;
    }

    private FrameLayout.LayoutParams bottomCentre() {
        // Just above the tray, so a message never covers the top row.
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = screenH - (gridTop - pad) + dp(14);
        return lp;
    }

    private TextView label(String s, float sp, int color, String family) {
        TextView t = new Tile.Flat(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Fonts.get(this, family));
        t.setIncludeFontPadding(false);
        return t;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /**
     * The dock's glass, drawn at a height between its compact size (just the cards) and its full
     * size (cards and names), so it can shrink without a relayout.
     */
    private static final class Tray extends View {
        private final android.graphics.drawable.Drawable glass;
        private final int compact, full;
        private float fill = 1f;

        Tray(Context c, android.graphics.drawable.Drawable glass, int compact, int full) {
            super(c);
            this.glass = glass;
            this.compact = compact;
            this.full = full;
        }

        void setFill(float f) { fill = f; invalidate(); }

        @Override protected void onDraw(android.graphics.Canvas c) {
            glass.setBounds(0, 0, getWidth(), Math.round(compact + (full - compact) * fill));
            glass.draw(c);
        }
    }

    /** Converts a pixel height into the sp value that renders at that height. */
    private static final class TypedValueSp {
        static float of(Context c, float px) { return px / c.getResources().getDisplayMetrics().scaledDensity; }
    }
}
