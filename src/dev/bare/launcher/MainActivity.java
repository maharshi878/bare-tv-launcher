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
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
 * Bare: one calm grid of the apps you chose, in the order you chose, on a backdrop lit by
 * whatever you're about to open. The first row is your top row. Hold OK on anything for
 * options, including Move. Nothing is recommended to you, nothing runs in the background.
 */
public class MainActivity extends Activity implements Tile.Host, Sheet.Listener {

    static final String ACTION_ADD = "dev.bare.launcher.ADD";

    private static final String PREFS = "bare";
    private static final String K_HIDDEN = "hidden";   // string set of entry keys
    private static final String K_ORDER = "order";     // JSON array of entry keys, as arranged
    private static final String K_PINNED = "pinned";   // v2 favourites; read once to seed the order
    private static final String K_CUSTOM = "custom";   // JSON array of {id,label,target}
    private static final String K_LAUNCHES = "launches";

    static final String KEY_INPUTS = "@inputs", KEY_SETTINGS = "@settings", KEY_ADD = "+";
    static final int COLS = 5;

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
    private Backdrop backdrop;
    private View dock, clock;
    private LinearLayout offline;
    private TextView toast, moveHint;
    private Sheet sheet;

    private int screenW, screenH, margin, gap, cardW, cardH, labelSpace, pitchY, gridTop, dockGap, topInset;
    private boolean rich, deep;
    private float scroll;
    private String focusKey;
    private Tile moving, menuFor;
    private final Runnable showAmbient = this::applyAmbient;
    private final Runnable hideToast = () -> toast.animate().alpha(0f).translationY(dp(12)).setDuration(220).start();

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
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        rich = am == null || !am.isLowRamDevice();
        measure();
        setContentView(buildUi());
        handleAdd(getIntent());

        int launches = prefs.getInt(K_LAUNCHES, 0);
        prefs.edit().putInt(K_LAUNCHES, launches + 1).apply();
        if (launches < 3) root.postDelayed(() -> say("Tip: hold OK on any app for options"), 1400);
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
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(pkgReceiver);
        unregisterReceiver(netReceiver);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (handleAdd(intent)) return;
        // Home pressed while already home: everything closes, back to the first tile.
        if (sheet.isOpen()) sheet.close(null);
        if (moving != null) endMove();
        if (!entries.isEmpty()) focus(tiles.get(entries.get(0).key), 0, 0);
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

    /** Everything is sized from the screen, so 1366x768 and 1920x1080 look the same. */
    private void measure() {
        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;
        margin = Math.round(screenW * 0.05f);
        gap = Math.round(screenW * 0.026f);
        cardW = (screenW - 2 * margin - (COLS - 1) * gap) / COLS;
        cardH = Math.round(cardW * 9f / 16f);
        labelSpace = Math.round(cardH * 0.52f);
        pitchY = cardH + labelSpace;
        gridTop = Math.round(screenH * 0.5f);
        dockGap = Math.round(screenH * 0.045f);
        topInset = Math.round(screenH * 0.1f);
    }

    private View buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Backdrop.BASE);
        backdrop = new Backdrop(this);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));

        content = new FrameLayout(this);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        root.addView(content, new FrameLayout.LayoutParams(-1, screenH));

        // The frosted shelf everything below the top row sits on.
        dock = new View(this);
        dock.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[] {
                new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { 0x00FFFFFF, 0x0CFFFFFF }),
                new android.graphics.drawable.ColorDrawable(0x0CFFFFFF) }));
        content.addView(dock, new FrameLayout.LayoutParams(-1, screenH * 4));

        clock = buildClock();
        content.addView(clock);

        offline = new LinearLayout(this);
        offline.setOrientation(LinearLayout.HORIZONTAL);
        offline.setGravity(Gravity.CENTER_VERTICAL);
        Glyph wifi = new Glyph(this, Glyph.WIFI_OFF);
        wifi.setColor(0xB3FFFFFF);
        offline.addView(wifi, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView ot = label("Not connected", 14, 0xB3FFFFFF, "sans-serif-medium");
        ot.setPadding(dp(10), 0, 0, 0);
        offline.addView(ot);
        FrameLayout.LayoutParams ol = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        ol.topMargin = Math.round(screenH * 0.06f);
        ol.rightMargin = margin;
        root.addView(offline, ol);

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

    private View buildClock() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        TextClock date = new TextClock(this);
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM");
        date.setFormat12Hour(pattern);
        date.setFormat24Hour(pattern);
        date.setTextSize(17);
        date.setTextColor(0xCCFFFFFF);
        date.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        date.setIncludeFontPadding(false);

        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("HH:mm");
        time.setTextSize(TypedValueSp.of(this, screenH * 0.135f));
        time.setTextColor(0xFFFFFFFF);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        time.setIncludeFontPadding(false);
        time.setLetterSpacing(-0.02f);

        box.addView(date);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.topMargin = dp(6);
        box.addView(time, tl);

        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
        lp.leftMargin = margin - dp(2);
        lp.topMargin = gridTop - Math.round(screenH * 0.065f) - box.getMeasuredHeight();
        box.setLayoutParams(lp);
        return box;
    }

    private int slotX(int i) { return margin + (i % COLS) * (cardW + gap); }
    private int slotY(int i) { int r = i / COLS; return gridTop + r * pitchY + (r > 0 ? dockGap : 0); }

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
        dock.setVisibility(entries.size() > COLS ? View.VISIBLE : View.INVISIBLE);
        float dockTop = gridTop + pitchY - labelSpace * 0.15f;
        dock.setTranslationY(dockTop);
        int bottom = slotY(Math.max(0, entries.size() - 1)) + pitchY + screenH;
        int dockH = bottom - Math.round(dockTop);
        android.graphics.drawable.LayerDrawable ld = (android.graphics.drawable.LayerDrawable) dock.getBackground();
        ld.setLayerInset(0, 0, 0, 0, dockH - (dockGap + labelSpace / 2));
        ld.setLayerInset(1, 0, dockGap + labelSpace / 2, 0, 0);
        if (dock.getLayoutParams().height != dockH) {
            dock.getLayoutParams().height = dockH;
            dock.requestLayout();
        }
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
        root.removeCallbacks(showAmbient);
        root.postDelayed(showAmbient, 90);
    }

    private void applyAmbient() {
        Tile t = tiles.get(focusKey);
        if (t != null) backdrop.show(Art.ambient(t.entry));
    }

    /** Keeps the focused row comfortably on screen. Row 0 is home: the clock is in view. */
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
        clock.animate().alpha(d ? 0f : 1f).setDuration(d ? 200 : 400).start();
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(d ? 0f : 1f, d ? 1f : 0f);
        va.setDuration(420);
        va.addUpdateListener(a -> backdrop.setDepth((float) a.getAnimatedValue()));
        va.start();
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
        if (sheet.isOpen()) return super.dispatchKeyEvent(e);
        int k = e.getKeyCode();
        int dx = k == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        int dy = k == KeyEvent.KEYCODE_DPAD_UP ? -1 : k == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        boolean arrow = dx != 0 || dy != 0;
        boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A;

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
        if (j < 0) cur.bump(dx, dy);
        else focus(tiles.get(entries.get(j).key), dx, dy);
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
        if (j < 0) { moving.bump(dx, dy); return; }
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
            if (e.icon != (r == null ? null : r.icon)) { e.icon = r == null ? null : r.icon; e.glow = 0; e.ambient = null; }
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
        offline.setVisibility(isOnline() ? View.GONE : View.VISIBLE);
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
                say(e.label + " hidden. Add › Hidden apps brings it back.");
                forget(e.key);
            });
        }
        if (e.kind == Entry.APP && !e.system) {
            sheet.row(Glyph.TRASH, "Uninstall", () ->
                    open(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + e.pkg)), null));
        }
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
        sheet.begin("Add", 320);
        sheet.row(Glyph.GLOBE, "Shortcut to an app or link…", this::showAddForm);
        if (!hidden.isEmpty()) sheet.row(Glyph.SHOW, "Hidden apps (" + hidden.size() + ")…", () -> showUnhide(t));
        present(t);
    }

    private void showAddForm() {
        sheet.begin("New shortcut", 440);
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

    private void showUnhide(Tile t) {
        final PackageManager pm = getPackageManager();
        final List<String> keys = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        Set<String> h = hidden();
        for (String k : h) {
            if (k.equals(KEY_INPUTS)) { keys.add(k); labels.add("Inputs"); continue; }
            if (k.equals(KEY_SETTINGS)) { keys.add(k); labels.add("Settings"); continue; }
            try {
                labels.add(String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(k, 0))));
                keys.add(k);
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        if (keys.size() != h.size()) { h.retainAll(keys); setHidden(h); }   // drop uninstalled packages
        if (keys.isEmpty()) { say("Nothing is hidden"); return; }
        final boolean[] show = new boolean[keys.size()];
        sheet.begin("Choose what to show again", 360);
        for (int i = 0; i < keys.size(); i++) sheet.check(labels.get(i), false, show, i);
        sheet.buttons(new String[] { "Show" }, new Runnable[] { () -> {
            Set<String> nh = hidden();
            for (int i = 0; i < show.length; i++) if (show[i]) nh.remove(keys.get(i));
            setHidden(nh);
            reload();
        } }, new boolean[] { true });
        present(t);
    }

    /** Opens the sheet beside {@code t} (or centred) with the rest of the grid stepped back. */
    private void present(Tile t) {
        menuFor = t;
        content.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        for (Tile o : tiles.values()) o.setRecede(o != t);
        if (t != null) t.setHeld(true);
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
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(24));
        bg.setColor(0xF0242428);
        bg.setStroke(1, 0x1FFFFFFF);
        t.setBackground(bg);
        t.setElevation(dp(12));
        return t;
    }

    private FrameLayout.LayoutParams bottomCentre() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = Math.round(screenH * 0.07f);
        return lp;
    }

    private TextView label(String s, float sp, int color, String family) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /** Converts a pixel height into the sp value that renders at that height. */
    private static final class TypedValueSp {
        static float of(Context c, float px) { return px / c.getResources().getDisplayMetrics().scaledDensity; }
    }
}
