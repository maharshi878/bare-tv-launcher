package dev.bare.launcher;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * A floating panel for menus, pickers and the one small form. It opens beside the tile it
 * belongs to, holds focus until it is dismissed, and hands focus back to that tile.
 * Replaces the stock dialogs, which look out of place on a TV.
 */
final class Sheet extends FrameLayout {
    interface Listener { void onSheetClosed(); }
    interface Choice { void chose(int index); }

    private final LinearLayout panel;
    /** Where rows go: the panel itself, or the scrolling list opened by {@link #beginList}. */
    private LinearLayout target;
    private final float d;
    private Listener listener;
    private boolean open;
    private View firstFocus;

    Sheet(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        setVisibility(GONE);
        setClickable(true);
        setClipChildren(false);

        panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        int p = dp(10);
        panel.setPadding(p, p, p, p);
        panel.setBackground(Glass.solid(dp(24), Math.max(1, dp(1))));
        panel.setElevation(dp(24));
        addView(panel, new LayoutParams(-2, -2));
    }

    void setListener(Listener l) { listener = l; }
    boolean isOpen() { return open; }

    // ---------------------------------------------------------------- building

    Sheet begin(String title, int widthDp) {
        panel.removeAllViews();
        target = panel;
        firstFocus = null;
        panel.setLayoutParams(new LayoutParams(dp(widthDp), -2));
        if (title != null) {
            TextView t = text(title, 14, 0x99FFFFFF, "sans-serif-medium");
            t.setSingleLine();
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setPadding(dp(16), dp(8), dp(16), dp(8));
            panel.addView(t);
        }
        return this;
    }

    /** A menu row. Selecting it closes the sheet, then runs {@code act}. */
    View row(int glyph, String label, final Runnable act) {
        final View r = rowView(glyph, label, false, null, null);
        r.setOnClickListener(v -> close(act));
        return r;
    }

    /** A setting: its current value on the right; OK steps to the next value without closing. */
    View option(int glyph, String label, final String[] values, int index, final Choice ch) {
        final TextView v = text(values[index], 15, 0x99FFFFFF, "sans-serif-medium");
        final View r = rowView(glyph, label, false, v, null);
        final int[] at = { index };
        r.setOnClickListener(x -> {
            at[0] = (at[0] + 1) % values.length;
            v.setText(values[at[0]]);
            ch.chose(at[0]);
        });
        return r;
    }

    /** A row with a check mark that toggles in place. */
    View check(String label, boolean initial, final boolean[] state, final int index) {
        return check(label, initial, state, index, null);
    }

    /** As {@link #check}, led by a small card of the app's own artwork, the same as its tile. */
    View check(String label, boolean initial, final boolean[] state, final int index, Entry art) {
        state[index] = initial;
        final View r = rowView(Glyph.CHECK, label, true, null, art == null ? null : thumb(art));
        final Glyph g = (Glyph) r.getTag();
        g.setVisibility(initial ? VISIBLE : INVISIBLE);
        r.setOnClickListener(v -> {
            state[index] = !state[index];
            g.setVisibility(state[index] ? VISIBLE : INVISIBLE);
        });
        return r;
    }

    /**
     * Rows added until {@link #endList} go into a list that scrolls once it is taller than
     * {@code maxHeightPx}. Focus moving onto a row out of view scrolls it in.
     */
    void beginList(int maxHeightPx) {
        final LinearLayout inner = new LinearLayout(getContext());
        inner.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(getContext()) {
            @Override protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST));
            }
        };
        sv.setVerticalScrollBarEnabled(true);
        sv.setFillViewport(false);
        sv.setClipToPadding(false);
        sv.addView(inner, new LayoutParams(-1, -2));
        panel.addView(sv, new LinearLayout.LayoutParams(-1, -2));
        target = inner;
    }

    void endList() { target = panel; }

    void note(String s) {
        TextView t = text(s, 13, 0x8CFFFFFF, "sans-serif");
        t.setLineSpacing(dp(3), 1f);
        t.setPadding(dp(16), dp(6), dp(16), dp(10));
        panel.addView(t);
    }

    EditText field(String hint) {
        final EditText e = new EditText(getContext());
        e.setHint(hint);
        e.setSingleLine();
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.setImeOptions(EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        e.setTextSize(16);
        e.setTextColor(0xFFFFFFFF);
        e.setHintTextColor(0x66FFFFFF);
        e.setPadding(dp(16), 0, dp(16), 0);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(0x14FFFFFF);
        bg.setStroke(dp(2), 0x00FFFFFF);
        e.setBackground(bg);
        e.setOnFocusChangeListener((v, f) -> {
            bg.setColor(f ? 0x26FFFFFF : 0x14FFFFFF);
            bg.setStroke(dp(2), f ? 0xE6FFFFFF : 0x00FFFFFF);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(50));
        lp.setMargins(dp(6), dp(4), dp(6), dp(6));
        panel.addView(e, lp);
        if (firstFocus == null) firstFocus = e;
        return e;
    }

    /** A row of pill buttons; the last one is the primary action. */
    View[] buttons(String[] labels, Runnable[] acts, boolean[] closes) {
        LinearLayout bar = new LinearLayout(getContext());
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.END);
        bar.setPadding(0, dp(8), 0, 0);
        View[] out = new View[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final TextView b = text(labels[i], 15, 0xFFFFFFFF, "sans-serif-medium");
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(22), 0, dp(22), 0);
            b.setFocusable(true);
            b.setClickable(true);
            final GradientDrawable bg = pill(0x1AFFFFFF);
            b.setBackground(bg);
            b.setOnFocusChangeListener((v, f) -> {
                bg.setColor(f ? 0xFFFFFFFF : 0x1AFFFFFF);
                b.setTextColor(f ? 0xFF111114 : 0xFFFFFFFF);
            });
            final Runnable act = acts[i];
            final boolean close = closes[i];
            b.setOnClickListener(v -> { if (close) close(act); else if (act != null) act.run(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(44));
            lp.leftMargin = dp(8);
            bar.addView(b, lp);
            out[i] = b;
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, dp(6), dp(4));
        panel.addView(bar, lp);
        return out;
    }

    // ---------------------------------------------------------------- showing

    /** Opens next to {@code anchor} (screen coordinates), or centred when it is null. */
    void show(Rect anchor) {
        open = true;
        setVisibility(VISIBLE);
        int pw = panel.getLayoutParams().width;
        panel.measure(MeasureSpec.makeMeasureSpec(pw, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(((View) getParent()).getHeight(), MeasureSpec.AT_MOST));
        int ph = panel.getMeasuredHeight();
        View parent = (View) getParent();
        int w = parent.getWidth(), h = parent.getHeight();
        int edgeX = Math.round(w * 0.05f), edgeY = Math.round(h * 0.05f);
        int x, y;
        boolean right = true;
        if (anchor != null) {
            int gap = dp(22);
            x = anchor.right + gap;
            if (x + pw > w - edgeX) { x = anchor.left - gap - pw; right = false; }
            // Fits on neither side of the tile: centre it instead of running off the screen.
            if (x < edgeX) { x = (w - pw) / 2; right = true; }
            y = Math.max(edgeY, Math.min(anchor.top - dp(10), h - edgeY - ph));
        } else {
            x = (w - pw) / 2;
            y = Math.max(edgeY, (h - ph) / 2 - dp(24));
        }
        LayoutParams lp = (LayoutParams) panel.getLayoutParams();
        lp.leftMargin = x;
        lp.topMargin = y;
        panel.setLayoutParams(lp);

        panel.setPivotX(anchor == null ? pw / 2f : right ? 0 : pw);
        panel.setPivotY(anchor == null ? ph / 2f : Math.max(0, Math.min(ph, anchor.centerY() - y)));
        panel.setAlpha(0f);
        panel.setScaleX(0.92f);
        panel.setScaleY(0.92f);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(Tile.EASE).start();

        final View first = firstFocus != null ? firstFocus : firstFocusable(panel);
        if (first != null) post(first::requestFocus);
    }

    /** Closes, then runs {@code then} (which may open another sheet). */
    void close(final Runnable then) {
        if (!open) return;
        open = false;
        panel.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(120).setInterpolator(Tile.EASE)
                .withEndAction(() -> { if (!open) setVisibility(GONE); }).start();
        if (listener != null) listener.onSheetClosed();
        if (then != null) post(then);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (open && e.getAction() == KeyEvent.ACTION_UP
                && (e.getKeyCode() == KeyEvent.KEYCODE_BACK || e.getKeyCode() == KeyEvent.KEYCODE_MENU
                    || e.getKeyCode() == KeyEvent.KEYCODE_ESCAPE)) {
            close(null);
            return true;
        }
        // Arrows move focus only within the panel; it can never wander onto the grid behind.
        int dir = 0;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP: dir = View.FOCUS_UP; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: dir = View.FOCUS_DOWN; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: dir = View.FOCUS_LEFT; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dir = View.FOCUS_RIGHT; break;
        }
        if (open && dir != 0) {
            View cur = panel.findFocus();
            boolean sideways = dir == View.FOCUS_LEFT || dir == View.FOCUS_RIGHT;
            if (cur instanceof EditText && sideways) return super.dispatchKeyEvent(e);   // caret keys
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                View next = FocusFinder.getInstance().findNextFocus(panel, cur, dir);
                if (next != null) next.requestFocus(dir);
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // ---------------------------------------------------------------- parts

    private View rowView(int glyph, String label, boolean glyphRight, final TextView value, View lead) {
        final LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), 0, dp(16), 0);
        r.setFocusable(true);
        r.setClickable(true);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(0x00FFFFFF);
        r.setBackground(bg);

        final Glyph g = new Glyph(getContext(), glyph);
        g.setColor(0xFFFFFFFF);
        final TextView t = text(label, 16, 0xFFFFFFFF, "sans-serif-medium");
        t.setSingleLine();
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(dp(22), dp(22));
        if (lead != null) {
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(dp(60), dp(36));
            ll.rightMargin = dp(16);
            r.addView(lead, ll);
        }
        if (glyphRight) {
            r.addView(t, tl);
            gl.leftMargin = dp(14);
            r.addView(g, gl);
        } else {
            gl.rightMargin = dp(14);
            r.addView(g, gl);
            r.addView(t, tl);
        }
        if (value != null) {
            LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(-2, -2);
            vl.leftMargin = dp(12);
            r.addView(value, vl);
        }
        r.setTag(g);
        r.setOnFocusChangeListener((v, f) -> {
            bg.setColor(f ? 0xFFFFFFFF : 0x00FFFFFF);
            t.setTextColor(f ? 0xFF111114 : 0xFFFFFFFF);
            g.setColor(f ? 0xFF111114 : 0xFFFFFFFF);
            if (value != null) value.setTextColor(f ? 0x99111114 : 0x99FFFFFF);
        });
        target.addView(r, new LinearLayout.LayoutParams(-1, dp(lead != null ? 56 : 50)));
        return r;
    }

    /** A 5:3 card of an app's artwork with rounded corners, drawn by the same code as the grid. */
    private View thumb(final Entry e) {
        View v = new View(getContext()) {
            @Override protected void onDraw(android.graphics.Canvas c) { Art.draw(c, e, getWidth(), getHeight()); }
        };
        final float r = dp(6);
        v.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
        return v;
    }

    private TextView text(String s, float sp, int color, String family) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Fonts.get(getContext(), family));
        t.setIncludeFontPadding(false);
        return t;
    }

    private GradientDrawable pill(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(22));
        g.setColor(color);
        return g;
    }

    private static View firstFocusable(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.isFocusable()) return c;
            if (c instanceof ViewGroup) { View f = firstFocusable((ViewGroup) c); if (f != null) return f; }
        }
        return null;
    }

    private int dp(int v) { return Math.round(v * d); }
}
