package dev.bare.launcher;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Small tactile sounds, synthesised on first run into the cache as WAV files (no audio
 * assets): a tick when focus moves, a tock when something opens, a soft knock at an edge, a
 * pop when a menu appears and a breath of air when the apps tuck away. They play on the media
 * stream, so they follow the TV's volume; the system-sounds stream TVs keep nearly silent.
 * SoundPool keeps them decoded, so playing one is cheap.
 */
final class Sounds {
    static final int OFF = 0, SOFT = 1, LOUD = 2;

    private static final int RATE = 44100;

    private final SoundPool pool;
    private final int tick, tock, thud, pop, air;
    private int level;

    Sounds(Context c, int level) {
        this.level = level;
        pool = new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        File dir = new File(c.getCacheDir(), "sounds-v3");
        dir.mkdirs();
        // Partials are {start Hz, end Hz, level, decay seconds}. TV speakers barely move below
        // ~200 Hz, so even the knock is built from what they can play.
        tick = load(dir, "tick", synth(0.034f, 0.9f, 0.0016f, new float[][] {
                { 1900, 1750, 1f, 0.0065f }, { 3800, 3500, 0.32f, 0.004f }, { 620, 560, 0.55f, 0.005f } }));
        tock = load(dir, "tock", synth(0.075f, 0.85f, 0.002f, new float[][] {
                { 1050, 720, 1f, 0.016f }, { 2100, 1440, 0.25f, 0.009f }, { 360, 300, 0.5f, 0.02f } }));
        thud = load(dir, "thud", synth(0.085f, 0.8f, 0.0025f, new float[][] {
                { 330, 230, 1f, 0.022f }, { 660, 460, 0.45f, 0.014f }, { 990, 700, 0.18f, 0.008f } }));
        pop = load(dir, "pop", synth(0.055f, 0.7f, 0.001f, new float[][] {
                { 900, 1500, 1f, 0.016f }, { 1800, 3000, 0.2f, 0.008f } }));
        air = load(dir, "air", breath(0.42f, 0.5f));
    }

    void setLevel(int l) { level = l; }
    int level() { return level; }

    void tick() { play(tick, 1f); }
    void tock() { play(tock, 0.9f); }
    void thud() { play(thud, 0.9f); }
    void pop() { play(pop, 0.8f); }
    void air() { play(air, 0.7f); }

    private void play(int id, float vol) {
        if (level == OFF || id == 0) return;
        float v = vol * (level == SOFT ? 0.4f : 1f);
        pool.play(id, v, v, 1, 0, 1f);
    }

    private int load(File dir, String name, short[] pcm) {
        File f = new File(dir, name + ".wav");
        try {
            if (!f.exists()) writeWav(f, pcm);
            return pool.load(f.getPath(), 1);
        } catch (IOException | RuntimeException e) {
            return 0;   // no sound is better than a crash
        }
    }

    /**
     * A few sines, each sweeping and decaying on its own, plus a burst of noise at the attack
     * for the "click" of a physical key. Normalised so every sound peaks at {@code peak}.
     */
    private static short[] synth(float secs, float peak, float click, float[][] partials) {
        int n = Math.round(secs * RATE);
        double[] s = new double[n];
        double[] phase = new double[partials.length];
        long seed = 0x2545F4914F6CDD1DL;
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE, u = i / (double) n, v = 0;
            for (int k = 0; k < partials.length; k++) {
                float[] p = partials[k];
                phase[k] += 2 * Math.PI * (p[0] + (p[1] - p[0]) * u) / RATE;
                v += Math.sin(phase[k]) * p[2] * Math.exp(-t / p[3]);
            }
            seed ^= seed << 13; seed ^= seed >>> 7; seed ^= seed << 17;
            double nz = ((seed >>> 11) / (double) (1L << 53)) * 2 - 1;
            v += nz * 0.35 * Math.exp(-t / click);
            s[i] = v * edges(i, n);
        }
        return normalise(s, peak);
    }

    /** Soft filtered noise that swells and fades: air moving as the apps slide away. */
    private static short[] breath(float secs, float peak) {
        int n = Math.round(secs * RATE);
        double[] s = new double[n];
        long seed = 0x9E3779B97F4A7C15L;
        double lp = 0, lp2 = 0;
        for (int i = 0; i < n; i++) {
            seed ^= seed << 13; seed ^= seed >>> 7; seed ^= seed << 17;
            double nz = ((seed >>> 11) / (double) (1L << 53)) * 2 - 1;
            double u = i / (double) n;
            double cut = 0.05 + 0.1 * Math.sin(Math.PI * u);   // the "whoosh" opens, then closes
            lp += cut * (nz - lp);
            lp2 += cut * (lp - lp2);
            double env = Math.pow(Math.sin(Math.PI * Math.min(1, u * 1.15)), 2);
            s[i] = lp2 * env * edges(i, n);
        }
        return normalise(s, peak);
    }

    /** 0.8 ms fade in and 3 ms fade out, so neither end clicks. */
    private static double edges(int i, int n) {
        return Math.min(1.0, i / (RATE * 0.0008)) * Math.min(1.0, (n - 1 - i) / (RATE * 0.003));
    }

    private static short[] normalise(double[] s, float peak) {
        double max = 1e-9;
        for (double v : s) max = Math.max(max, Math.abs(v));
        short[] out = new short[s.length];
        for (int i = 0; i < s.length; i++) out[i] = (short) Math.round(s[i] / max * peak * 32767);
        return out;
    }

    private static void writeWav(File f, short[] pcm) throws IOException {
        int data = pcm.length * 2;
        byte[] b = new byte[44 + data];
        put(b, 0, "RIFF"); le32(b, 4, 36 + data); put(b, 8, "WAVE");
        put(b, 12, "fmt "); le32(b, 16, 16); le16(b, 20, 1); le16(b, 22, 1);
        le32(b, 24, RATE); le32(b, 28, RATE * 2); le16(b, 32, 2); le16(b, 34, 16);
        put(b, 36, "data"); le32(b, 40, data);
        for (int i = 0; i < pcm.length; i++) le16(b, 44 + 2 * i, pcm[i]);
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(b); }
        if (!tmp.renameTo(f)) throw new IOException("rename failed");
    }

    private static void put(byte[] b, int at, String s) { for (int i = 0; i < 4; i++) b[at + i] = (byte) s.charAt(i); }
    private static void le16(byte[] b, int at, int v) { b[at] = (byte) v; b[at + 1] = (byte) (v >> 8); }
    private static void le32(byte[] b, int at, int v) { le16(b, at, v); le16(b, at + 2, v >> 16); }
}
