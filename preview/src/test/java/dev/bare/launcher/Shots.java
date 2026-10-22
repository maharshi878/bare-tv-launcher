package dev.bare.launcher;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.PixelCopy;
import android.view.View;
import android.widget.EditText;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;

import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/**
 * Drives the real launcher through its states with key presses and saves each frame.
 * Output: preview/out/*.png. 960x540dp at xhdpi is a 1080p TV; tvdpi is a 1366x768 one.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w960dp-h540dp-land-xhdpi")
public class Shots {
    private static final File OUT = new File(System.getProperty("bare.out", "out"));
    private static final String ORDER =
            "[\"com.netflix.ninja\",\"com.google.android.youtube.tv\",\"org.jellyfin.androidtv\",\"in.startv.hotstar\",\"@inputs\"]";

    @Before public void reset() {
        PreviewActivity.empty = false;
        PreviewActivity.offline = false;
        Wallpaper.sync = true;
        OUT.mkdirs();
        prefs().edit().clear().putInt("launches", 9).commit();
    }

    private static android.content.SharedPreferences prefs() {
        return RuntimeEnvironment.getApplication().getSharedPreferences("bare", Context.MODE_PRIVATE);
    }

    private Activity launch() {
        // A TV driven by a remote is never in touch mode.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false);
        ActivityController<PreviewActivity> c = Robolectric.buildActivity(PreviewActivity.class).setup();
        idle(1200);
        return c.get();
    }

    @Test public void firstRun() throws Exception {
        prefs().edit().putInt("launches", 0).commit();
        Activity a = launch();
        idle(1600);
        shot(a, "01-first-run");
    }

    @Test public void home() throws Exception {
        prefs().edit().putString("order", ORDER).commit();
        Activity a = launch();
        shot(a, "02-home");
        press(a, KeyEvent.KEYCODE_DPAD_RIGHT);
        press(a, KeyEvent.KEYCODE_DPAD_RIGHT);
        shot(a, "03-home-jellyfin");
    }

    @Test public void grid() throws Exception {
        prefs().edit().putString("order", ORDER).commit();
        Activity a = launch();
        press(a, KeyEvent.KEYCODE_DPAD_DOWN);
        shot(a, "04-grid-row2");
        press(a, KeyEvent.KEYCODE_DPAD_DOWN);
        press(a, KeyEvent.KEYCODE_DPAD_DOWN);
        press(a, KeyEvent.KEYCODE_DPAD_RIGHT);
        shot(a, "05-grid-bottom");
    }

    @Test public void menu() throws Exception {
        prefs().edit().putString("order", ORDER).commit();
        Activity a = launch();
        press(a, KeyEvent.KEYCODE_DPAD_RIGHT);
        press(a, KeyEvent.KEYCODE_MENU);
        shot(a, "06-menu");
        press(a, KeyEvent.KEYCODE_DPAD_CENTER);   // "Move"
        press(a, KeyEvent.KEYCODE_DPAD_RIGHT);
        shot(a, "07-moving");
        press(a, KeyEvent.KEYCODE_DPAD_CENTER);
        shot(a, "08-moved");
        assertTrue(prefs().getString("order", "").startsWith(
                "[\"com.netflix.ninja\",\"org.jellyfin.androidtv\",\"com.google.android.youtube.tv\""));
    }

    @Test public void hideAndBringBack() throws Exception {
        prefs().edit().putString("order", ORDER).commit();
        Activity a = launch();
        press(a, KeyEvent.KEYCODE_MENU);
        press(a, KeyEvent.KEYCODE_DPAD_DOWN);   // Move, App info,
        press(a, KeyEvent.KEYCODE_DPAD_DOWN);   // Hide
        press(a, KeyEvent.KEYCODE_DPAD_CENTER);
        shot(a, "14-hidden");
        assertTrue(prefs().getStringSet("hidden", null).contains("com.netflix.ninja"));
        assertTrue(a.getWindow().getDecorView().findFocus() instanceof Tile);
    }

    @Test public void inputsAndAdd() throws Exception {
        prefs().edit().putString("order", ORDER).commit();
        Activity a = launch();
        for (int i = 0; i < 4; i++) press(a, KeyEvent.KEYCODE_DPAD_RIGHT);
        press(a, KeyEvent.KEYCODE_DPAD_CENTER);
        shot(a, "09-inputs");
        press(a, KeyEvent.KEYCODE_BACK);
        for (int i = 0; i < 3; i++) press(a, KeyEvent.KEYCODE_DPAD_DOWN);
        press(a, KeyEvent.KEYCODE_DPAD_RIGHT);
        press(a, KeyEvent.KEYCODE_DPAD_RIGHT);
        press(a, KeyEvent.KEYCODE_DPAD_CENTER);
        shot(a, "10-add-menu");
        press(a, KeyEvent.KEYCODE_DPAD_CENTER);
        View f = a.getCurrentFocus();
        if (f instanceof EditText) ((EditText) f).setText("Jellyfin web");
        idle(400);
        shot(a, "11-add-form");
    }

    @Test @Config(qualifiers = "w1026dp-h577dp-land-tvdpi")
    public void hd768() throws Exception {
        prefs().edit().putString("order", ORDER).commit();
        Activity a = launch();
        press(a, KeyEvent.KEYCODE_DPAD_RIGHT);
        shot(a, "12-1366x768");
    }

    /** The owner's TV: 1280x720 at 213 dpi (wm size / wm density, read 2026-10-20). */
    @Test @Config(qualifiers = "w960dp-h540dp-land-tvdpi")
    public void hd720() throws Exception {
        prefs().edit().putString("order", ORDER).commit();
        Activity a = launch();
        press(a, KeyEvent.KEYCODE_DPAD_RIGHT);
        shot(a, "15-1280x720-home");
        press(a, KeyEvent.KEYCODE_DPAD_DOWN);
        shot(a, "16-1280x720-grid");
        press(a, KeyEvent.KEYCODE_DPAD_UP);
        press(a, KeyEvent.KEYCODE_DPAD_UP);
        shot(a, "17-1280x720-calm");
        press(a, KeyEvent.KEYCODE_DPAD_DOWN);   // wakes; does nothing else
        shot(a, "18-1280x720-awake");
        press(a, KeyEvent.KEYCODE_MENU);
        for (int i = 0; i < 6; i++) press(a, KeyEvent.KEYCODE_DPAD_DOWN);
        press(a, KeyEvent.KEYCODE_DPAD_CENTER);   // "Bare settings…"
        shot(a, "19-1280x720-settings");
    }

    @Test @Config(qualifiers = "w960dp-h540dp-land-tvdpi")
    public void idleTucksAway() throws Exception {
        prefs().edit().putString("order", ORDER).commit();
        Activity a = launch();
        idle(61_000);
        shot(a, "20-1280x720-idle");
        assertTrue(a.getWindow().getDecorView().findFocus() instanceof Tile);
    }

    @Test public void emptyOffline() throws Exception {
        PreviewActivity.empty = true;
        PreviewActivity.offline = true;
        Activity a = launch();
        shot(a, "13-empty-offline");
    }

    // ---------------------------------------------------------------- helpers

    private static void idle(long ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    private static void press(Activity a, int key) {
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
        idle(30);
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
        idle(1100);
    }

    private static void shot(Activity a, String name) throws Exception {
        View root = a.getWindow().getDecorView();
        Bitmap b = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        final int[] result = { -1 };
        root.invalidate();
        idle(32);
        if (!Boolean.getBoolean("bare.sw")) try {
            PixelCopy.request(a.getWindow(), b, r -> result[0] = r, new Handler(Looper.getMainLooper()));
            idle(50);
        } catch (RuntimeException ignored) { }
        if (result[0] != PixelCopy.SUCCESS) root.draw(new Canvas(b));
        try (FileOutputStream f = new FileOutputStream(new File(OUT, name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, f);
        }
        System.out.println("shot " + name + " " + b.getWidth() + "x" + b.getHeight() + (result[0] == 0 ? " (hw)" : " (sw)"));
    }
}
