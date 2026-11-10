package de.aufstehwecker.app;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Vergleicht zwei Fotos grob: Helligkeitsmuster (lichtunabhängig), Kanten und Farbverteilung.
 * Kleine Verschiebungen werden toleriert. Ergebnis 0..1.
 */
final class ImageMatcher {
    private ImageMatcher() {}

    private static final int N = 40;
    private static final int SHIFT = 4;

    static float similarity(Bitmap a, Bitmap b) {
        Bitmap sa = small(a), sb = small(b);
        float[] ga = gray(sa), gb = gray(sb);
        float ncc = clamp(bestNcc(ga, gb));
        float edges = clamp(bestNcc(gradient(ga), gradient(gb)));
        float hist = histIntersection(sa, sb);
        return 0.4f * ncc + 0.3f * edges + 0.3f * hist;
    }

    /** Standardabweichung der Helligkeit – sehr klein = schwarzes/abgedecktes Bild. */
    static float contrast(Bitmap b) {
        float[] g = gray(small(b));
        float mean = 0;
        for (float v : g) mean += v;
        mean /= g.length;
        float var = 0;
        for (float v : g) var += (v - mean) * (v - mean);
        return (float) Math.sqrt(var / g.length);
    }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

    private static Bitmap small(Bitmap b) {
        int s = Math.min(b.getWidth(), b.getHeight());
        int x = (b.getWidth() - s) / 2, y = (b.getHeight() - s) / 2;
        Bitmap c = Bitmap.createBitmap(b, x, y, s, s);
        return Bitmap.createScaledBitmap(c, N, N, true);
    }

    private static float[] gray(Bitmap small) {
        int[] px = new int[N * N];
        small.getPixels(px, 0, N, 0, 0, N, N);
        float[] g = new float[N * N];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            g[i] = (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) / 255f;
        }
        return g;
    }

    private static float[] gradient(float[] g) {
        float[] out = new float[N * N];
        for (int y = 1; y < N - 1; y++) {
            for (int x = 1; x < N - 1; x++) {
                float gx = g[y * N + x + 1] - g[y * N + x - 1];
                float gy = g[(y + 1) * N + x] - g[(y - 1) * N + x];
                out[y * N + x] = Math.abs(gx) + Math.abs(gy);
            }
        }
        return out;
    }

    private static float bestNcc(float[] a, float[] b) {
        float best = -1f;
        int lo = SHIFT, hi = N - SHIFT;
        int count = (hi - lo) * (hi - lo);
        for (int dy = -SHIFT; dy <= SHIFT; dy++) {
            for (int dx = -SHIFT; dx <= SHIFT; dx++) {
                float ma = 0, mb = 0;
                for (int y = lo; y < hi; y++) {
                    for (int x = lo; x < hi; x++) {
                        ma += a[y * N + x];
                        mb += b[(y + dy) * N + x + dx];
                    }
                }
                ma /= count;
                mb /= count;
                float cov = 0, va = 0, vb = 0;
                for (int y = lo; y < hi; y++) {
                    for (int x = lo; x < hi; x++) {
                        float pa = a[y * N + x] - ma;
                        float pb = b[(y + dy) * N + x + dx] - mb;
                        cov += pa * pb;
                        va += pa * pa;
                        vb += pb * pb;
                    }
                }
                if (va <= 1e-6f || vb <= 1e-6f) continue;
                float ncc = (float) (cov / Math.sqrt(va * vb));
                if (ncc > best) best = ncc;
            }
        }
        return best;
    }

    private static float histIntersection(Bitmap a, Bitmap b) {
        float[] ha = hist(a), hb = hist(b);
        float sum = 0;
        for (int i = 0; i < ha.length; i++) sum += Math.min(ha[i], hb[i]);
        return sum;
    }

    /** 12 Farbton-Fächer × 2 Helligkeiten + 4 Grau-Fächer. */
    private static float[] hist(Bitmap small) {
        int[] px = new int[N * N];
        small.getPixels(px, 0, N, 0, 0, N, N);
        float[] h = new float[28];
        float[] hsv = new float[3];
        for (int p : px) {
            Color.colorToHSV(p, hsv);
            int bin;
            if (hsv[1] < 0.2f || hsv[2] < 0.15f) {
                bin = 24 + Math.min(3, (int) (hsv[2] * 4));
            } else {
                int hue = Math.min(11, (int) (hsv[0] / 30f));
                bin = hue * 2 + (hsv[2] < 0.55f ? 0 : 1);
            }
            h[bin] += 1f;
        }
        for (int i = 0; i < h.length; i++) h[i] /= px.length;
        return h;
    }
}
