package dev.bare.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * Makes the Home button open Bare while the TV's own home screen stays installed and enabled.
 * Android TV firmware gives its stock launcher a higher HOME priority than any app can have,
 * so "default home" settings never take; instead this accessibility service, once the owner
 * turns it on in Settings, does two things and nothing else:
 *
 *  - Home pressed: Bare opens (the press is consumed, so the stock screen never flashes).
 *  - A stock home screen came to the front anyway (after boot, or when an app exits to it):
 *    Bare opens over it.
 *
 * It reads no window content. If Bare can't stay up, a loop guard stops redirecting after a
 * few tries, so the stock home screen is always reachable. Turning the service off in
 * Settings > Accessibility puts everything back exactly as it was.
 */
public class HomeKey extends AccessibilityService {
    /** Set while the service is connected, so Bare's settings can say whether it's on. */
    static volatile boolean running;
    /** Set by Bare when the owner asks for the stock home screen; cleared by the next Home press. */
    static volatile boolean visitingStock;

    private final long[] recent = new long[3];
    private int next;
    private long pausedUntil;
    private Set<String> stockHomes = new HashSet<>();

    @Override
    protected void onServiceConnected() {
        running = true;
        stockHomes = findStockHomes();
        // The service connects as the TV finishes booting, with the stock home screen in front.
        if (SystemClock.elapsedRealtime() < 5 * 60 * 1000L) openBare();
    }

    @Override
    public boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) {
            visitingStock = false;
            pausedUntil = 0;
            if (!openBare()) performGlobalAction(GLOBAL_ACTION_HOME);
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent ev) {
        if (ev.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || visitingStock) return;
        CharSequence cls = ev.getClassName(), pkg = ev.getPackageName();
        if (cls == null || pkg == null || !stockHomes.contains(pkg + "/" + cls)) return;
        long now = SystemClock.elapsedRealtime();
        if (now < pausedUntil) return;
        // Three redirects inside 20 seconds means Bare isn't staying up: leave the stock screen be.
        if (recent[next] != 0 && now - recent[next] < 20_000) { pausedUntil = now + 5 * 60_000; return; }
        recent[next] = now;
        next = (next + 1) % recent.length;
        openBare();
    }

    @Override
    public void onInterrupt() { }

    @Override
    public boolean onUnbind(Intent intent) {
        running = false;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    private boolean openBare() {
        try {
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Every other HOME activity on the TV, as "package/class", except the boot-time FallbackHome. */
    private Set<String> findStockHomes() {
        Set<String> out = new HashSet<>();
        try {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(q, PackageManager.MATCH_DISABLED_COMPONENTS)) {
                String pkg = ri.activityInfo.packageName, cls = ri.activityInfo.name;
                if (pkg.equals(getPackageName()) || cls.endsWith("FallbackHome")) continue;
                out.add(pkg + "/" + cls);
            }
        } catch (RuntimeException ignored) { }
        return out;
    }
}
