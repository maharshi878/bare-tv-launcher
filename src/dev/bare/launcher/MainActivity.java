package dev.bare.launcher;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URISyntaxException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bare — a Google-TV-style home screen with nothing recommended to you.
 * Top bar (search, Home/Apps, inputs, network, settings, clock), a hero for whatever is
 * focused, a Favorites row and a Your-apps row. Long-press OK (or MENU) for options.
 */
public class MainActivity extends Activity {

    static final String ACTION_ADD = "dev.bare.launcher.ADD";

    private static final String PREFS = "bare";
    private static final String K_HIDDEN = "hidden";   // string set of package names
    private static final String K_PINNED = "pinned";   // JSON array of entry keys, in order
    private static final String K_CUSTOM = "custom";   // JSON array of {id,label,target}

    // Google TV's dark palette
    private static final int ON_SURFACE = 0xFFE3E3E8;
    private static final int ON_SURFACE_DIM = 0xFFA9AAB1;
    private static final int SURFACE = 0xFF26272B;
    private static final int SURFACE_SEL = 0xFF3C3D43;
    private static final int INK = 0xFF121316;

    private static final int HOME = 0, APPS = 1;

    private final List<Entry> favorites = new ArrayList<>();
    private final List<Entry> homeApps = new ArrayList<>();
    private final List<Entry> allApps = new ArrayList<>();
    private final List<Tile> homeTiles = new ArrayList<>();
    private final List<Tile> gridTiles = new ArrayList<>();

    private SharedPreferences prefs;
    private GlowView glow;
    private ScrollView homeScroll, appsScroll;
    private LinearLayout favRow, appRow, grid;
    private FrameLayout heroArt;
    private TextView heroTitle, heroSub, favTitle;
    private TextView tabHome, tabApps;
    private IconView netIcon;
    private int page = HOME;
    private int margin, tileW, gridTileW;
    private String lastKey;

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { reload(); }
    };
    private final BroadcastReceiver netReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateNet(); }
    };

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int w = getResources().getDisplayMetrics().widthPixels;
        int pad = Tile.sizeFor(0, this) / 2;
        margin = dp(48);
        tileW = Math.round((w - margin) / 4.6f);
        gridTileW = Math.round((w - 2 * margin + 2 * pad) / 5f);
        setContentView(buildUi());
        handleAdd(getIntent());
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
        // Home pressed while already home: back to the top of Home.
        showPage(HOME);
        homeScroll.scrollTo(0, 0);
        if (!homeTiles.isEmpty()) homeTiles.get(0).requestFocus();
    }

    @Override public void onBackPressed() { }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (f instanceof Tile) { showMenu(((Tile) f).entry); return true; }
        }
        return super.onKeyDown(keyCode, e);
    }

    /** `adb shell am start -a dev.bare.launcher.ADD --es name X --es target Y` */
    private boolean handleAdd(Intent i) {
        if (i == null || !ACTION_ADD.equals(i.getAction())) return false;
        String name = i.getStringExtra("name"), target = i.getStringExtra("target");
        if (target == null || target.trim().isEmpty()) return true;
        if (resolve(target) == null) {
            Toast.makeText(this, "Bare: can't resolve " + target, Toast.LENGTH_LONG).show();
            return true;
        }
        addCustom(name == null || name.trim().isEmpty() ? target : name, target);
        return true;
    }

    // ------------------------------------------------------------ UI

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        glow = new GlowView(this);
        root.addView(glow, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        col.addView(buildTopBar());

        FrameLayout pages = new FrameLayout(this);
        homeScroll = scroll();
        homeScroll.addView(buildHome());
        appsScroll = scroll();
        appsScroll.addView(buildGrid());
        pages.addView(homeScroll, new FrameLayout.LayoutParams(-1, -1));
        pages.addView(appsScroll, new FrameLayout.LayoutParams(-1, -1));
        col.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1f));

        showPage(HOME);
        return root;
    }

    private ScrollView scroll() {
        ScrollView s = new ScrollView(this);
        s.setVerticalScrollBarEnabled(false);
        s.setOverScrollMode(View.OVER_SCROLL_NEVER);
        s.setClipChildren(false);
        s.setClipToPadding(false);
        s.setFillViewport(true);
        return s;
    }

    // --- top bar

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(margin - dp(4), dp(22), margin, dp(10));

        bar.addView(searchPill());
        space(bar, dp(18));
        tabHome = tab("Home", HOME);
        tabApps = tab("Apps", APPS);
        bar.addView(tabHome);
        bar.addView(tabApps);

        View fill = new View(this);
        bar.addView(fill, new LinearLayout.LayoutParams(0, 1, 1f));

        bar.addView(iconButton(IconView.INPUT, this::showInputs));
        netIcon = new IconView(this, IconView.WIFI);
        bar.addView(iconButton(netIcon, () -> open(new Intent(Settings.ACTION_WIFI_SETTINGS))));
        bar.addView(iconButton(IconView.GEAR, () -> open(new Intent(Settings.ACTION_SETTINGS))));

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(ON_SURFACE);
        clock.setTextSize(17);
        clock.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        clock.setPadding(dp(14), 0, 0, 0);
        bar.addView(clock);
        return bar;
    }

    private void space(LinearLayout l, int w) { l.addView(new View(this), new LinearLayout.LayoutParams(w, 1)); }

    private View searchPill() {
        final LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setPadding(dp(16), 0, dp(16), 0);
        p.setFocusable(true);
        p.setClickable(true);
        final GradientDrawable bg = pill(SURFACE);
        p.setBackground(bg);

        final IconView s = new IconView(this, IconView.SEARCH);
        final TextView t = new TextView(this);
        t.setText("Search");
        t.setTextSize(15);
        t.setTextColor(ON_SURFACE_DIM);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        final IconView m = new IconView(this, IconView.MIC);
        p.addView(s, new LinearLayout.LayoutParams(dp(20), dp(20)));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.leftMargin = dp(12);
        p.addView(t, tl);
        p.addView(m, new LinearLayout.LayoutParams(dp(20), dp(20)));
        p.setLayoutParams(new LinearLayout.LayoutParams(dp(210), dp(44)));

        p.setOnFocusChangeListener((v, f) -> {
            bg.setColor(f ? Color.WHITE : SURFACE);
            int ink = f ? INK : ON_SURFACE;
            s.setColor(ink);
            m.setColor(ink);
            t.setTextColor(f ? 0xFF55565C : ON_SURFACE_DIM);
        });
        s.setColor(ON_SURFACE);
        m.setColor(ON_SURFACE);
        p.setOnClickListener(v -> search());
        return p;
    }

    private TextView tab(String label, final int target) {
        final TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(dp(20), 0, dp(20), 0);
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackground(pill(Color.TRANSPARENT));
        t.setLayoutParams(new LinearLayout.LayoutParams(-2, dp(44)));
        // Like Google TV, a tab switches the content the moment it takes focus.
        t.setOnFocusChangeListener((v, f) -> {
            if (f) showPage(target);
            styleTabs();
        });
        t.setOnClickListener(v -> {
            showPage(target);
            View first = firstTile(target);
            if (first != null) first.requestFocus();
        });
        return t;
    }

    private void styleTabs() {
        for (TextView t : new TextView[] { tabHome, tabApps }) {
            boolean selected = (t == tabHome) == (page == HOME);
            boolean focused = t.isFocused();
            ((GradientDrawable) t.getBackground()).setColor(focused ? Color.WHITE : selected ? SURFACE_SEL : Color.TRANSPARENT);
            t.setTextColor(focused ? INK : selected ? Color.WHITE : ON_SURFACE_DIM);
        }
    }

    private View iconButton(int type, Runnable action) { return iconButton(new IconView(this, type), action); }

    private View iconButton(final IconView icon, final Runnable action) {
        FrameLayout f = new FrameLayout(this);
        f.setFocusable(true);
        f.setClickable(true);
        final GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.TRANSPARENT);
        f.setBackground(bg);
        f.addView(icon, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        f.setOnFocusChangeListener((v, focused) -> {
            bg.setColor(focused ? Color.WHITE : Color.TRANSPARENT);
            icon.setColor(focused ? INK : ON_SURFACE);
        });
        f.setOnClickListener(v -> action.run());
        return f;
    }

    private GradientDrawable pill(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(22));
        g.setColor(color);
        return g;
    }

    // --- Home page: hero + rows

    private View buildHome() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setClipChildren(false);
        page.setClipToPadding(false);
        page.setPadding(0, 0, 0, dp(48));

        FrameLayout hero = new FrameLayout(this);
        hero.setClipChildren(false);
        int artW = Math.round(getResources().getDisplayMetrics().widthPixels * 0.34f);
        int artH = Math.round(artW * 9f / 16f);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setGravity(Gravity.CENTER_VERTICAL);
        heroTitle = new TextView(this);
        heroTitle.setTextColor(Color.WHITE);
        heroTitle.setTextSize(36);
        heroTitle.setSingleLine();
        heroTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heroTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heroSub = new TextView(this);
        heroSub.setTextColor(ON_SURFACE_DIM);
        heroSub.setTextSize(15);
        heroSub.setPadding(0, dp(8), 0, 0);
        TextView hint = new TextView(this);
        hint.setText("Press and hold OK for options");
        hint.setTextColor(0x77FFFFFF);
        hint.setTextSize(13);
        hint.setPadding(0, dp(22), 0, 0);
        text.addView(heroTitle);
        text.addView(heroSub);
        text.addView(hint);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(
                getResources().getDisplayMetrics().widthPixels - artW - margin * 2 - dp(24), -1);
        tl.leftMargin = margin;
        hero.addView(text, tl);

        heroArt = new FrameLayout(this);
        FrameLayout.LayoutParams al = new FrameLayout.LayoutParams(artW, artH, Gravity.END | Gravity.CENTER_VERTICAL);
        al.rightMargin = margin;
        hero.addView(heroArt, al);
        page.addView(hero, new LinearLayout.LayoutParams(-1, dp(214)));

        favTitle = rowTitle("Favorites");
        page.addView(favTitle);
        favRow = new LinearLayout(this);
        page.addView(rowScroller(favRow));

        page.addView(rowTitle("Your apps"));
        appRow = new LinearLayout(this);
        page.addView(rowScroller(appRow));
        return page;
    }

    private TextView rowTitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(ON_SURFACE);
        t.setTextSize(17);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(margin, dp(14), 0, dp(10));
        return t;
    }

    private HorizontalScrollView rowScroller(LinearLayout inner) {
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.setOverScrollMode(View.OVER_SCROLL_NEVER);
        h.setClipChildren(false);
        h.setClipToPadding(false);
        h.setPadding(margin - Tile.sizeFor(0, this) / 2, 0, margin, 0);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setClipChildren(false);
        inner.setClipToPadding(false);
        h.addView(inner);
        return h;
    }

    // --- Apps page: grid

    private View buildGrid() {
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(margin - Tile.sizeFor(0, this) / 2, dp(14), margin, dp(48));
        return grid;
    }

    private Tile tile(Entry e, int tileWidth) {
        int cardW = tileWidth - Tile.sizeFor(0, this);
        int cardH = Math.round(cardW * 9f / 16f);
        Tile t = new Tile(this, e, cardW, cardH);
        t.setLayoutParams(new LinearLayout.LayoutParams(tileWidth, Tile.sizeFor(cardH, this)));
        t.setOnClickListener(v -> activate(e));
        t.setOnLongClickListener(v -> { showMenu(e); return true; });
        t.onFocused = this::onTileFocused;
        return t;
    }

    private void onTileFocused(Tile t) {
        Entry e = t.entry;
        lastKey = e.key;
        glow.setGlow(e.glow);

        heroTitle.setText(e.add ? "Add to home" : e.label);
        heroSub.setText(subtitle(e));
        heroArt.removeAllViews();
        int w = heroArt.getLayoutParams().width, h = heroArt.getLayoutParams().height;
        heroArt.addView(Tile.card(this, e, w, h, 16, 1.7f), new FrameLayout.LayoutParams(w, h));
        for (View v : new View[] { heroTitle, heroSub, heroArt }) {
            v.setAlpha(0f);
            v.animate().alpha(1f).setDuration(180).start();
        }
    }

    private String subtitle(Entry e) {
        if (e.add) return "An app, a link, or something you hid";
        if (e.custom) return e.target;
        StringBuilder sb = new StringBuilder();
        try {
            String v = getPackageManager().getPackageInfo(e.pkg, 0).versionName;
            if (v != null) sb.append("Version ").append(v);
        } catch (PackageManager.NameNotFoundException ignored) { }
        if (pinned().contains(e.key)) sb.append(sb.length() > 0 ? "  ·  " : "").append("Favorite");
        return sb.toString();
    }

    private View firstTile(int p) {
        List<Tile> l = p == HOME ? homeTiles : gridTiles;
        return l.isEmpty() ? null : l.get(0);
    }

    private void showPage(int p) {
        page = p;
        homeScroll.setVisibility(p == HOME ? View.VISIBLE : View.GONE);
        appsScroll.setVisibility(p == APPS ? View.VISIBLE : View.GONE);
        styleTabs();
    }

    private void updateNet() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        boolean up = ni != null && ni.isConnected();
        netIcon.setSlash(!up);
    }

    // ------------------------------------------------------------ data

    private void reload() {
        PackageManager pm = getPackageManager();
        Set<String> hidden = hidden();
        Map<String, Entry> byPkg = new HashMap<>();

        for (String cat : new String[] { Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER }) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || hidden.contains(pkg) || byPkg.containsKey(pkg)) continue;
                Entry e = new Entry();
                e.key = pkg;
                e.pkg = pkg;
                e.label = String.valueOf(ri.loadLabel(pm));
                e.icon = ri.loadIcon(pm);
                e.banner = ri.activityInfo.loadBanner(pm);
                e.glow = dominant(e.banner != null ? e.banner : e.icon, e.label);
                e.system = (ri.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                e.launch = new Intent(Intent.ACTION_MAIN).addCategory(cat)
                        .setClassName(pkg, ri.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                byPkg.put(pkg, e);
            }
        }

        final Collator col = Collator.getInstance();
        Comparator<Entry> byLabel = new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) { return col.compare(a.label, b.label); }
        };
        List<Entry> apps = new ArrayList<>(byPkg.values());
        Collections.sort(apps, byLabel);

        List<Entry> customs = new ArrayList<>();
        JSONArray cs = customs();
        for (int i = 0; i < cs.length(); i++) {
            JSONObject o = cs.optJSONObject(i);
            if (o == null) continue;
            Entry e = new Entry();
            e.custom = true;
            e.customId = o.optString("id");
            e.key = "c:" + e.customId;
            e.label = o.optString("label");
            e.target = o.optString("target");
            Resolved r = resolve(e.target);
            e.launch = r == null ? null : r.intent;
            e.icon = r == null ? null : r.icon;
            e.glow = dominant(e.icon, e.label);
            customs.add(e);
        }
        Collections.sort(customs, byLabel);

        Map<String, Entry> byKey = new HashMap<>(byPkg);
        for (Entry c : customs) byKey.put(c.key, c);

        List<String> pins = pinned();
        favorites.clear();
        homeApps.clear();
        allApps.clear();
        for (String k : pins) { Entry e = byKey.get(k); if (e != null) favorites.add(e); }
        for (Entry c : customs) if (!favorites.contains(c)) favorites.add(c);
        for (Entry a : apps) if (!pins.contains(a.key)) homeApps.add(a);
        allApps.addAll(apps);
        allApps.addAll(customs);

        Entry add = new Entry();
        add.add = true;
        add.key = "+";
        add.label = "Add";
        add.glow = 0xFF3A4B73;
        favorites.add(add);
        allApps.add(add);

        rebuildViews();
    }

    private void rebuildViews() {
        homeTiles.clear();
        gridTiles.clear();
        favRow.removeAllViews();
        appRow.removeAllViews();
        grid.removeAllViews();

        for (Entry e : favorites) { Tile t = tile(e, tileW); favRow.addView(t); homeTiles.add(t); }
        for (Entry e : homeApps)  { Tile t = tile(e, tileW); appRow.addView(t); homeTiles.add(t); }

        final int cols = 5;
        LinearLayout row = null;
        for (int i = 0; i < allApps.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                grid.addView(row);
            }
            Tile t = tile(allApps.get(i), gridTileW);
            row.addView(t);
            gridTiles.add(t);
        }

        List<Tile> src = page == HOME ? homeTiles : gridTiles;
        Tile focus = src.isEmpty() ? null : src.get(0);
        for (Tile t : src) if (t.entry.key.equals(lastKey)) { focus = t; break; }
        final Tile f = focus;
        if (f != null) f.post(f::requestFocus);
    }

    /** A vivid, darkened version of the artwork's main color, used to tint plates and the hero wash. */
    private static int dominant(Drawable art, String seed) {
        if (art != null) {
            try {
                Drawable d = art.getConstantState() != null ? art.getConstantState().newDrawable().mutate() : art;
                Bitmap b = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
                d.setBounds(0, 0, 24, 24);
                d.draw(new Canvas(b));
                float r = 0, g = 0, bl = 0, wsum = 0;
                float[] hsv = new float[3];
                for (int y = 0; y < 24; y++) for (int x = 0; x < 24; x++) {
                    int p = b.getPixel(x, y);
                    if (Color.alpha(p) < 128) continue;
                    Color.colorToHSV(p, hsv);
                    float w = hsv[1] * hsv[2];
                    if (w < 0.12f) continue;
                    w *= w;
                    r += Color.red(p) * w; g += Color.green(p) * w; bl += Color.blue(p) * w; wsum += w;
                }
                b.recycle();
                if (wsum > 0.05f) {
                    Color.colorToHSV(Color.rgb((int) (r / wsum), (int) (g / wsum), (int) (bl / wsum)), hsv);
                    hsv[1] = Math.min(0.85f, Math.max(0.5f, hsv[1]));
                    hsv[2] = 0.7f;
                    return Color.HSVToColor(hsv);
                }
            } catch (RuntimeException ignored) { }
        }
        return Color.HSVToColor(new float[] { Math.abs(seed.hashCode()) % 360, 0.5f, 0.62f });
    }

    // ------------------------------------------------------------ prefs

    private Set<String> hidden() {
        return new HashSet<>(prefs.getStringSet(K_HIDDEN, new HashSet<String>()));
    }

    private List<String> pinned() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString(K_PINNED, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (JSONException ignored) { }
        return out;
    }

    private void savePinned(List<String> p) {
        prefs.edit().putString(K_PINNED, new JSONArray(p).toString()).apply();
    }

    private JSONArray customs() {
        try { return new JSONArray(prefs.getString(K_CUSTOM, "[]")); }
        catch (JSONException e) { return new JSONArray(); }
    }

    private void addCustom(String label, String target) {
        try {
            JSONArray a = customs();
            a.put(new JSONObject().put("id", String.valueOf(System.currentTimeMillis()))
                    .put("label", label.trim()).put("target", target.trim()));
            prefs.edit().putString(K_CUSTOM, a.toString()).apply();
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
        List<String> p = pinned();
        p.remove("c:" + id);
        savePinned(p);
        lastKey = null;
        reload();
    }

    // ------------------------------------------------------------ custom targets

    private static final class Resolved {
        Intent intent;
        Drawable icon;
    }

    /**
     * A target is one of: an installed package name, "pkg/.Activity", an intent: URI,
     * or a link (anything with "://", or a bare host like youtube.com).
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

    private void activate(Entry e) {
        if (e.add) { showAddMenu(); return; }
        if (e.launch == null) {
            Toast.makeText(this, e.label + " isn't installed", Toast.LENGTH_SHORT).show();
            return;
        }
        open(e.launch);
    }

    private void open(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException ex) {
            Toast.makeText(this, "Nothing on this TV can open that", Toast.LENGTH_SHORT).show();
        }
    }

    private void search() {
        for (String action : new String[] { Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND }) {
            try {
                startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) { }
        }
        Toast.makeText(this, "No search app on this TV", Toast.LENGTH_SHORT).show();
    }

    /** Lists HDMI/AV sources straight from the system's TV input service. */
    private void showInputs() {
        final List<TvInputInfo> inputs = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        try {
            TvInputManager tm = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            for (TvInputInfo info : tm.getTvInputList()) {
                if (!info.isPassthroughInput()) continue;
                CharSequence custom = info.loadCustomLabel(this);
                inputs.add(info);
                names.add(String.valueOf(custom != null ? custom : info.loadLabel(this)));
            }
        } catch (RuntimeException ignored) { }
        if (inputs.isEmpty()) {
            Toast.makeText(this, "No inputs reported by this TV", Toast.LENGTH_SHORT).show();
            return;
        }
        dialog().setTitle("Inputs").setItems(names.toArray(new String[0]), (d, w) ->
                open(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputs.get(w).getId()))))
                .show();
    }

    // ------------------------------------------------------------ menus

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK);
    }

    private void showMenu(final Entry e) {
        if (e.add) { showAddMenu(); return; }
        final List<String> items = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();

        final List<String> pins = pinned();
        final boolean isPinned = pins.contains(e.key);
        items.add(isPinned ? "Remove from Favorites" : "Add to Favorites");
        acts.add(() -> {
            if (isPinned) pins.remove(e.key); else pins.add(e.key);
            savePinned(pins);
            reload();
        });

        if (e.custom) {
            items.add("Remove");
            acts.add(() -> removeCustom(e.customId));
        } else {
            items.add("App info");
            acts.add(() -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg))));
            if (!e.system) {
                items.add("Uninstall");
                acts.add(() -> open(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + e.pkg))));
            }
            items.add("Hide from home");
            acts.add(() -> {
                Set<String> h = hidden();
                h.add(e.pkg);
                prefs.edit().putStringSet(K_HIDDEN, h).apply();
                lastKey = null;
                reload();
            });
        }
        dialog().setTitle(e.label).setItems(items.toArray(new String[0]), (d, w) -> acts.get(w).run()).show();
    }

    private void showAddMenu() {
        final Set<String> hidden = hidden();
        final List<String> items = new ArrayList<>();
        items.add("Custom shortcut…");
        if (!hidden.isEmpty()) items.add("Show hidden apps (" + hidden.size() + ")…");
        dialog().setTitle("Add to home").setItems(items.toArray(new String[0]), (d, w) -> {
            if (w == 0) showAddForm(); else showUnhide(hidden);
        }).show();
    }

    private void showAddForm() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);

        final EditText name = field("Name  (e.g. Jellyfin)");
        final EditText target = field("Package, link or pkg/.Activity");
        box.addView(name);
        box.addView(target);

        TextView help = new TextView(this);
        help.setText("org.jellyfin.androidtv\nyoutube.com/tv\ncom.example.app/.MainActivity");
        help.setTextColor(0x88FFFFFF);
        help.setTextSize(12);
        help.setPadding(dp(4), dp(12), 0, dp(8));
        box.addView(help);

        final AlertDialog dlg = dialog().setTitle("Custom shortcut").setView(box)
                .setPositiveButton("Add", null).setNegativeButton("Cancel", null).create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String t = target.getText().toString();
            if (resolve(t) == null) {
                Toast.makeText(this, "Can't find that app or link", Toast.LENGTH_SHORT).show();
                return;
            }
            String n = name.getText().toString().trim();
            addCustom(n.isEmpty() ? t.trim() : n, t);
            dlg.dismiss();
        });
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine();
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(0x66FFFFFF);
        return e;
    }

    private void showUnhide(Set<String> hidden) {
        final PackageManager pm = getPackageManager();
        final List<String> pkgs = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        for (String p : hidden) {
            try {
                labels.add(String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(p, 0))));
                pkgs.add(p);
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        final boolean[] checked = new boolean[pkgs.size()];
        dialog().setTitle("Show on home")
                .setMultiChoiceItems(labels.toArray(new String[0]), checked, (d, i, c) -> checked[i] = c)
                .setPositiveButton("Show", (d, w) -> {
                    Set<String> h = hidden();
                    for (int i = 0; i < checked.length; i++) if (checked[i]) h.remove(pkgs.get(i));
                    prefs.edit().putStringSet(K_HIDDEN, h).apply();
                    reload();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
