package dev.bare.launcher;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/** Charcoal backdrop with a faint wash of the focused app's color in the top-right corner. */
final class GlowView extends View {
    static final int BASE = 0xFF0E0F12;
    private final ArgbEvaluator argb = new ArgbEvaluator();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int current = 0xFF3A4B73, from = current, to = current;
    private ValueAnimator anim;

    GlowView(Context c) { super(c); }

    void setGlow(int color) {
        if (color == to) return;
        from = current;
        to = color;
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(450);
        anim.addUpdateListener(a -> {
            current = (Integer) argb.evaluate(a.getAnimatedFraction(), from, to);
            invalidate();
        });
        anim.start();
    }

    private static int alpha(int c, int a) { return Color.argb(a, Color.red(c), Color.green(c), Color.blue(c)); }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        canvas.drawColor(BASE);
        paint.setShader(new RadialGradient(w * 0.78f, h * 0.2f, w * 0.7f,
                new int[] { alpha(current, 0x66), alpha(current, 0x24), 0x00000000 },
                new float[] { 0f, 0.55f, 1f }, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
    }
}
