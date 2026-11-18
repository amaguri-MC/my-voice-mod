package net.amaguri.myvoice.client.audio.dsp;

/**
 * Pure-Java pitch / tempo changer for 16-bit LE mono PCM.
 * One WSOLA time-stretch (factor pitch/speed) followed by one resample (by pitch).
 * Output length ~= input / speed, fundamental ~= input * pitch.
 */
public final class VoiceDsp {
    private VoiceDsp() {}

    public static byte[] process(byte[] pcm16le, int sampleRate, float pitch, float speed) {
        if (pcm16le == null) return new byte[0];
        int n = pcm16le.length / 2;
        if (pitch == 1f && speed == 1f) return pcm16le.clone();
        if (n == 0 || sampleRate <= 0) return new byte[n * 2];
        if (!(pitch > 0f) || Float.isInfinite(pitch)) pitch = 1f;
        if (!(speed > 0f) || Float.isInfinite(speed)) speed = 1f;
        pitch = Math.max(0.25f, Math.min(4f, pitch));
        speed = Math.max(0.25f, Math.min(4f, speed));

        float[] x = new float[n];
        for (int i = 0; i < n; i++) {
            x[i] = (short) ((pcm16le[2 * i] & 0xFF) | (pcm16le[2 * i + 1] << 8)) / 32768f;
        }

        double p = pitch;
        double ratio = p / speed;                 // WSOLA stretch factor
        float[] y = stretch(x, sampleRate, ratio);
        int m = Math.max(1, (int) Math.round(n / (double) speed));
        float[] out = resample(y, p, m);

        // fades
        int fade = Math.min(m / 2, Math.max(1, sampleRate * 4 / 1000));
        for (int i = 0; i < fade; i++) {
            float g = (float) i / fade;
            out[i] *= g;
            out[m - 1 - i] *= g;
        }

        byte[] res = new byte[m * 2];
        for (int i = 0; i < m; i++) {
            float v = out[i] * 32768f;
            int s = Math.round(v);
            if (s > 32767) s = 32767; else if (s < -32768) s = -32768;
            res[2 * i] = (byte) s;
            res[2 * i + 1] = (byte) (s >> 8);
        }
        return res;
    }

    /** WSOLA: returns array of length ~= x.length * ratio. */
    private static float[] stretch(float[] x, int sr, double ratio) {
        int n = x.length;
        int outLen = Math.max(1, (int) Math.round(n * ratio));
        int win = (int) (sr * 0.025) & ~1;
        if (win < 16 || n < win * 2) {
            // too short for WSOLA: plain linear stretch
            float[] y = new float[outLen];
            for (int i = 0; i < outLen; i++) {
                double pos = outLen > 1 ? (double) i * (n - 1) / (outLen - 1) : 0;
                y[i] = interp(x, pos);
            }
            return y;
        }
        int hs = win / 2;
        double ha = hs / ratio;
        int tol = Math.max(4, (int) (sr * 0.010));
        float[] w = new float[win];
        for (int i = 0; i < win; i++) w[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / win));

        int pad = win + tol + 8;
        float[] xp = new float[n + 2 * pad + win];
        System.arraycopy(x, 0, xp, pad, n);
        float[] y = new float[outLen + 2 * win];

        int prev = 0;
        for (int k = 0; (long) k * hs < outLen; k++) {
            int nom = (int) Math.round(k * ha);
            int pos = nom;
            if (k > 0) {
                int ref = prev + hs;  // natural continuation of previous frame
                pos = search(xp, pad, ref, nom, win, tol, n);
            }
            prev = pos;
            int o = k * hs;
            int base = pos + pad;
            for (int i = 0; i < win; i++) {
                float wi = (k == 0 && i < hs) ? 1f : w[i];
                y[o + i] += xp[base + i] * wi;
            }
        }
        return java.util.Arrays.copyOf(y, outLen);
    }

    private static int search(float[] xp, int pad, int ref, int nom, int win, int tol, int n) {
        int lo = Math.max(-pad + 1, nom - tol);
        int hi = Math.min(n + pad / 2, nom + tol);
        if (lo > hi) return Math.max(lo, Math.min(hi, nom));
        int rb = ref + pad;
        int best = Math.max(lo, Math.min(hi, nom));
        // coarse
        double bs = -1e30;
        for (int c = lo; c <= hi; c += 4) {
            double s = score(xp, rb, c + pad, win, 4);
            if (s > bs) { bs = s; best = c; }
        }
        // refine
        int rlo = Math.max(lo, best - 4), rhi = Math.min(hi, best + 4);
        bs = -1e30;
        int fin = best;
        for (int c = rlo; c <= rhi; c++) {
            double s = score(xp, rb, c + pad, win, 2);
            if (s > bs) { bs = s; fin = c; }
        }
        return fin;
    }

    private static double score(float[] xp, int rb, int cb, int win, int step) {
        if (rb < 0 || cb < 0 || rb + win > xp.length || cb + win > xp.length) return -1e30;
        double dot = 0, en = 1e-9;
        for (int i = 0; i < win; i += step) {
            float c = xp[cb + i];
            dot += xp[rb + i] * c;
            en += c * c;
        }
        return dot / Math.sqrt(en);
    }

    private static float interp(float[] a, double pos) {
        int i = (int) pos;
        if (i < 0) return a[0];
        if (i >= a.length - 1) return a[a.length - 1];
        float f = (float) (pos - i);
        return a[i] + (a[i + 1] - a[i]) * f;
    }

    /** Read y at positions i*p for i in [0,m). Windowed-sinc lowpass when p > 1. */
    private static float[] resample(float[] y, double p, int m) {
        float[] out = new float[m];
        int len = y.length;
        if (p <= 1.0001) {
            for (int i = 0; i < m; i++) out[i] = interp(y, i * p);
            return out;
        }
        double fc = 1.0 / p * 0.95;      // cutoff relative to input Nyquist*... (cycles/sample)*2
        int half = (int) Math.ceil(6 * p);
        int taps = 2 * half + 2;
        final int PH = 128;
        float[][] tab = new float[PH][taps];   // tab[ph][t]: tap at j = i0 - half + t, frac = ph/PH
        for (int ph = 0; ph < PH; ph++) {
            double fr = (double) ph / PH, ws = 0;
            for (int t = 0; t < taps; t++) {
                double d = (t - half) - fr;
                double u = d * fc;
                double sinc = Math.abs(u) < 1e-9 ? 1.0 : Math.sin(Math.PI * u) / (Math.PI * u);
                double k = sinc * (0.5 + 0.5 * Math.cos(Math.PI * Math.max(-1, Math.min(1, d / (half + 1)))));
                tab[ph][t] = (float) k;
                ws += k;
            }
            for (int t = 0; t < taps; t++) tab[ph][t] /= (float) ws;
        }
        for (int i = 0; i < m; i++) {
            double c = i * p;
            int i0 = (int) Math.floor(c);
            float[] kk = tab[Math.min(PH - 1, (int) ((c - i0) * PH))];
            int j0 = i0 - half;
            float acc = 0;
            if (j0 >= 0 && j0 + taps <= len) {
                for (int t = 0; t < taps; t++) acc += kk[t] * y[j0 + t];
            } else {
                for (int t = 0; t < taps; t++) { int j = j0 + t; if (j >= 0 && j < len) acc += kk[t] * y[j]; }
            }
            out[i] = acc;
        }
        return out;
    }
}
