/*
Copyright (c) 2017, Agustin Trujillo-Pino
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution

* Neither the name of University Las Palmas Gran Canaria nor the names of its
  contributors may be used to endorse or promote products derived from this
  software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

package br.rededor.fisicamedica;

import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import java.util.ArrayList;
import java.util.List;

/**
 * Subpixel edge detector based on the partial-area effect
 * (Trujillo-Pino et al., Image and Vision Computing 31(1), 2013),
 * with iterative restoration (section 6 of the paper).
 *
 * Port of finalDetectorIterN.m. The same logic handles "horizontal" edges
 * (|Gy| dominant, 9x3 window) and "vertical" edges (|Gx| dominant, 3x9 window):
 * the code works in (c, k) coordinates, where c is the transverse offset
 * (columns -1, 0, +1 of the window) and k the offset along it.
 *
 * Coordinate convention: the x and y of EdgePixel are 0-based with the pixel
 * CENTER at integer values. To use them in ImageJ ROIs (vertices at pixel
 * corners) add 0.5 to x and y.
 */
public class PartialAreaEdgeDetector {

    /**
     * "Nearby second edge" test (section 5.2 of the paper).
     * false = literal from finalDetectorIterN.m (compares the smoothed image G).
     * true  = as described in the paper (section 5.2, Figs. 15d/16c: uses the derivative).
     */
    public static boolean defaultCloseEdgeOnGradient = false;

    /**
     * Diagnostic (A/B). false = the synthetic patch uses each edge's original window
     * (as in finalDetectorIterN.m). true = reproduces the previous version of this class,
     * which passed the patch the limits ALREADY changed by the nearby-edge correction.
     */
    public static boolean legacyPatchWindow = false;

    private static final double W = 0.75;               // uniform 3x3 mask
    private static final double MAX_VALID_OFFSET = 1.0;
    private static final double R_MIN = 4.5;
    private static final double R_MAX = 1e4;
    private static final double WEIGHT_MIDDLE = 100.0;
    private static final int GRID = 50;                 // sampling per partial pixel
    private static final double HALF_DIAGONAL = Math.sqrt(0.5) + 1e-9;

    public static class EdgePixel {
        public double x, y;
        public double nx, ny;
        public double curvature;
        public double i0, i1;
    }

    public static class Result {
        public FloatProcessor restoredImage;
        public List<EdgePixel> edges;
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    public static Result detectAndRestore(ImageProcessor ip, double threshold, int order) {
        return detectAndRestore(ip, threshold, order, defaultCloseEdgeOnGradient);
    }

    public static Result detectAndRestore(ImageProcessor ip, double threshold, int order,
                                          boolean closeEdgeOnGradient) {
        double[][] f = toArray(ip);
        Context ctx = run(f, threshold, order, closeEdgeOnGradient);
        return toResult(ctx, finish(ctx));
    }

    public static Result detectAndRestoreIterative(ImageProcessor ip, double threshold,
                                                   int order, int iterations) {
        return detectAndRestoreIterative(ip, threshold, order, iterations,
                defaultCloseEdgeOnGradient);
    }

    /** Keeps the restored image in double precision between iterations (as in MATLAB). */
    public static Result detectAndRestoreIterative(ImageProcessor ip, double threshold,
                                                   int order, int iterations,
                                                   boolean closeEdgeOnGradient) {
        double[][] current = toArray(ip);
        Context last = null;
        double[][] restored = null;
        int n = Math.max(1, iterations);
        for (int it = 0; it < n; it++) {
            last = run(current, threshold, order, closeEdgeOnGradient);
            restored = finish(last);
            current = restored;
        }
        return toResult(last, restored);
    }

    // ------------------------------------------------------------------
    // State of a single detection pass
    // ------------------------------------------------------------------

    private static final class Context {
        final int w, h;
        final double[][] F, G, Gx, Gy, grad;
        final double threshold;
        final int order;
        final boolean gradClose;
        final double[][] acc, cnt;
        final List<EdgePixel> edges = new ArrayList<>();

        Context(double[][] f, double threshold, int order, boolean gradClose) {
            this.F = f;
            this.h = f.length;
            this.w = h > 0 ? f[0].length : 0;
            this.threshold = threshold;
            this.order = order;
            this.gradClose = gradClose;

            // 3x3 smoothing (image borders stay equal to F)
            G = new double[h][w];
            for (int y = 0; y < h; y++) System.arraycopy(f[y], 0, G[y], 0, w);
            for (int y = 1; y < h - 1; y++) {
                for (int x = 1; x < w - 1; x++) {
                    double sum = 0;
                    for (int j = -1; j <= 1; j++)
                        for (int i = -1; i <= 1; i++)
                            sum += f[y + j][x + i];
                    G[y][x] = sum / 9.0;
                }
            }

            // central derivatives; as in MATLAB, Gx covers all rows and Gy all columns
            Gx = new double[h][w];
            Gy = new double[h][w];
            grad = new double[h][w];
            for (int y = 0; y < h; y++)
                for (int x = 1; x < w - 1; x++)
                    Gx[y][x] = 0.5 * (G[y][x + 1] - G[y][x - 1]);
            for (int y = 1; y < h - 1; y++)
                for (int x = 0; x < w; x++)
                    Gy[y][x] = 0.5 * (G[y + 1][x] - G[y - 1][x]);
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    grad[y][x] = Math.hypot(Gx[y][x], Gy[y][x]);

            acc = new double[h][w];
            cnt = new double[h][w];
        }
    }

    private static Context run(double[][] f, double threshold, int order, boolean gradClose) {
        Context ctx = new Context(f, threshold, order, gradClose);
        final int w = ctx.w, h = ctx.h;

        // edges with maximal |Gy| (9x3 window)
        for (int y = 5; y <= h - 6; y++) {
            for (int x = 2; x <= w - 3; x++) {
                if (!(ctx.grad[y][x] > threshold)) continue;
                double absGy = Math.abs(ctx.Gy[y][x]);
                if (absGy >= Math.abs(ctx.Gx[y][x])
                        && absGy >= Math.abs(ctx.Gy[y - 1][x])
                        && absGy > Math.abs(ctx.Gy[y + 1][x])) {
                    processEdgePixel(ctx, x, y, true);
                }
            }
        }

        // edges with maximal |Gx| (3x9 window)
        for (int y = 2; y <= h - 3; y++) {
            for (int x = 5; x <= w - 6; x++) {
                if (!(ctx.grad[y][x] > threshold)) continue;
                double absGx = Math.abs(ctx.Gx[y][x]);
                if (absGx > Math.abs(ctx.Gy[y][x])
                        && absGx >= Math.abs(ctx.Gx[y][x - 1])
                        && absGx > Math.abs(ctx.Gx[y][x + 1])) {
                    processEdgePixel(ctx, x, y, false);
                }
            }
        }
        return ctx;
    }

    /** Final image: I/C where C>0, otherwise G. */
    private static double[][] finish(Context ctx) {
        double[][] out = new double[ctx.h][ctx.w];
        for (int y = 0; y < ctx.h; y++) {
            for (int x = 0; x < ctx.w; x++) {
                double c = ctx.cnt[y][x];
                out[y][x] = (c > 0) ? ctx.acc[y][x] / c : ctx.G[y][x];
            }
        }
        return out;
    }

    private static Result toResult(Context ctx, double[][] restored) {
        FloatProcessor out = new FloatProcessor(ctx.w, ctx.h);
        for (int y = 0; y < ctx.h; y++)
            for (int x = 0; x < ctx.w; x++)
                out.setf(x, y, (float) restored[y][x]);
        Result r = new Result();
        r.restoredImage = out;
        r.edges = ctx.edges;
        return r;
    }

    private static double[][] toArray(ImageProcessor ip) {
        FloatProcessor fp = (FloatProcessor) ip.convertToFloat();
        int w = fp.getWidth(), h = fp.getHeight();
        double[][] f = new double[h][w];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                f[y][x] = fp.getf(x, y);
        return f;
    }

    // ------------------------------------------------------------------
    // Access in (transverse c, longitudinal k) coordinates
    // ------------------------------------------------------------------

    /** dom=true: (x+c, y+k); dom=false: (x+k, y+c). */
    private static double at(double[][] a, int x, int y, int c, int k, boolean dom) {
        return dom ? a[y + k][x + c] : a[y + c][x + k];
    }

    // ------------------------------------------------------------------
    // Core: a single edge pixel
    // ------------------------------------------------------------------

    private static void processEdgePixel(Context ctx, int x, int y, boolean dom) {
        final double[][] F = ctx.F, G = ctx.G;
        final double[][] Gm = dom ? ctx.Gy : ctx.Gx;   // dominant gradient
        final double gx = ctx.Gx[y][x];
        final double gy = ctx.Gy[y][x];
        final double gm = dom ? gy : gx;

        // ---- floating window limits (section 5.1) ----
        final int m;
        final int minl1, maxr2, maxl2, minr1;
        int l1, l2, m1 = -1, m2 = 1, r1, r2;
        if (gx * gy >= 0) {
            m = 1; l1 = -1; r2 = 1; minl1 = -3; maxr2 = 3; l2 = 1; r1 = -1; maxl2 = 4; minr1 = -4;
        } else {
            m = -1; l1 = -1; r2 = 1; minl1 = -4; maxr2 = 4; l2 = 1; r1 = -1; maxl2 = 3; minr1 = -3;
        }

        while (l1 > minl1 && Math.abs(at(Gm, x, y, -1, l1, dom)) >= Math.abs(at(Gm, x, y, -1, l1 - 1, dom))) l1--;
        while (l2 < maxl2 && Math.abs(at(Gm, x, y, -1, l2, dom)) >= Math.abs(at(Gm, x, y, -1, l2 + 1, dom))) l2++;
        while (m1 > -4 && Math.abs(at(Gm, x, y, 0, m1, dom)) >= Math.abs(at(Gm, x, y, 0, m1 - 1, dom))) m1--;
        while (m2 < 4 && Math.abs(at(Gm, x, y, 0, m2, dom)) >= Math.abs(at(Gm, x, y, 0, m2 + 1, dom))) m2++;
        while (r1 > minr1 && Math.abs(at(Gm, x, y, 1, r1, dom)) >= Math.abs(at(Gm, x, y, 1, r1 - 1, dom))) r1--;
        while (r2 < maxr2 && Math.abs(at(Gm, x, y, 1, r2, dom)) >= Math.abs(at(Gm, x, y, 1, r2 + 1, dom))) r2++;

        // Synthetic-patch window: fixed BEFORE the nearby-edge correction
        // (in finalDetectorIterN.m, 'window' is built before uBorder/dBorder).
        final int wl1 = l1, wl2 = l2, wm1 = m1, wm2 = m2, wr1 = r1, wr2 = r2;

        // ---- intensities A and B ----
        double AA, BB;
        if (m > 0) {
            AA = (at(G, x, y, 0, m2, dom) + at(G, x, y, 1, r2, dom)) / 2.0;
            BB = (at(G, x, y, -1, l1, dom) + at(G, x, y, 0, m1, dom)) / 2.0;
        } else {
            AA = (at(G, x, y, -1, l2, dom) + at(G, x, y, 0, m2, dom)) / 2.0;
            BB = (at(G, x, y, 0, m1, dom) + at(G, x, y, 1, r1, dom)) / 2.0;
        }

        // ---- very close second edge (section 5.2) ----
        boolean uBorder = false, dBorder = false;
        final double[][] T = ctx.gradClose ? Gm : G;
        final double central = Math.abs(T[y][x]) / 4.0;
        final double half = ctx.threshold / 2.0;
        if (m1 > -4) {
            double p = Math.abs(at(T, x, y, 0, m1 - 2, dom));
            if (p > central && p > half) uBorder = true;
        }
        if (m2 < 4) {
            double p = Math.abs(at(T, x, y, 0, m2 + 2, dom));
            if (p > central && p > half) dBorder = true;
        }

        double SL = 0, SM = 0, SR = 0;

        if (uBorder || dBorder) {
            // intermediate subimage F' (11 x 5, in k x c coordinates)
            double[][] rim = new double[11][5];
            for (int k = -5; k <= 5; k++)
                for (int c = -2; c <= 2; c++)
                    rim[k + 5][c + 2] = at(F, x, y, c, k, dom);

            if (uBorder) {
                final int p = (m > 0) ? 1 : 0;
                if (m > 0) BB = (at(F, x, y, 0, m1, dom) + at(F, x, y, -1, l1, dom)) / 2.0;
                else       BB = (at(F, x, y, 0, m1, dom) + at(F, x, y, 1, r1, dom)) / 2.0;
                int ll = (at(Gm, x, y, -2, l1 + p, dom) * gm > 0) ? (l1 + p - 1) : (l1 + p);
                int rr = (at(Gm, x, y, 2, r1 + 1 - p, dom) * gm > 0) ? (r1 - p) : (r1 + 1 - p);
                fillFromStart(rim, 0, ll, BB);
                fillFromStart(rim, 1, l1, BB);
                fillFromStart(rim, 2, m1, BB);
                fillFromStart(rim, 3, r1, BB);
                fillFromStart(rim, 4, rr, BB);
                l1 = -3 + m; m1 = -3; r1 = -3 - m;
            }
            if (dBorder) {
                final int p = (m > 0) ? 1 : 0;
                if (m > 0) AA = (at(F, x, y, 0, m2, dom) + at(F, x, y, 1, r2, dom)) / 2.0;
                else       AA = (at(F, x, y, 0, m2, dom) + at(F, x, y, -1, l2, dom)) / 2.0;
                int ll = (at(Gm, x, y, -2, l2 + p - 1, dom) * gm > 0) ? (l2 + p) : (l2 + p - 1);
                int rr = (at(Gm, x, y, 2, r2 - p, dom) * gm > 0) ? (r2 + 1 - p) : (r2 - p);
                fillToEnd(rim, 0, ll, AA);
                fillToEnd(rim, 1, l2, AA);
                fillToEnd(rim, 2, m2, AA);
                fillToEnd(rim, 3, r2, AA);
                fillToEnd(rim, 4, rr, AA);
                l2 = 3 + m; m2 = 3; r2 = 3 - m;
            }

            // 3x3 smoothing of F' -> G' (9 x 3)
            double[][] g2 = new double[9][3];
            for (int kk = 0; kk < 9; kk++) {
                for (int cc = 0; cc < 3; cc++) {
                    double sum = 0;
                    for (int dk = 0; dk <= 2; dk++)
                        for (int dc = 0; dc <= 2; dc++)
                            sum += rim[kk + dk][cc + dc];
                    g2[kk][cc] = sum / 9.0;
                }
            }
            for (int n = l1 + 4; n <= l2 + 4; n++) SL += g2[n][0];
            for (int n = m1 + 4; n <= m2 + 4; n++) SM += g2[n][1];
            for (int n = r1 + 4; n <= r2 + 4; n++) SR += g2[n][2];
        } else {
            for (int n = l1; n <= l2; n++) SL += at(G, x, y, -1, n, dom);
            for (int n = m1; n <= m2; n++) SM += at(G, x, y, 0, n, dom);
            for (int n = r1; n <= r2; n++) SR += at(G, x, y, 1, n, dom);
        }

        // ---- parabola coefficients (eq. 20) ----
        final double den = 2.0 * (AA - BB);
        if (den == 0.0) return;

        double c = 0.0;
        if (ctx.order == 2) {
            c = (SL + SR - 2 * SM + AA * (2 * m2 - l2 - r2) - BB * (2 * m1 - l1 - r1)) / den;
        }
        final double a = (2 * SM - AA * (1 + 2 * m2) - BB * (1 - 2 * m1)) / den - W * c;
        if (!(Math.abs(a) <= MAX_VALID_OFFSET)) return;   // also discards NaN
        final double b = (SR - SL + AA * (l2 - r2) - BB * (l1 - r1)) / den;

        final double sgn = Math.signum(AA - BB);
        final double inv = sgn / Math.sqrt(1 + b * b);
        double curvature = 2 * c / Math.pow(1 + b * b, 1.5);
        if (gm < 0) curvature = -curvature;

        EdgePixel ep = new EdgePixel();
        if (dom) {
            ep.x = x;
            ep.y = y - a;
            ep.nx = inv * b;
            ep.ny = inv;
        } else {
            ep.x = x - a;
            ep.y = y;
            ep.nx = inv;
            ep.ny = inv * b;
        }
        ep.curvature = curvature;
        ep.i0 = Math.min(AA, BB);
        ep.i1 = Math.max(AA, BB);
        ctx.edges.add(ep);

        if (legacyPatchWindow) {
            synthesizePatch(ctx, ep, x, y, dom, l1, l2, m1, m2, r1, r2);
        } else {
            synthesizePatch(ctx, ep, x, y, dom, wl1, wl2, wm1, wm2, wr1, wr2);
        }
    }

    /** rim[0..lim+5][col] = v  (rows k = -5 .. lim). */
    private static void fillFromStart(double[][] rim, int col, int lim, double v) {
        int end = Math.min(lim + 5, rim.length - 1);
        for (int r = 0; r <= end; r++) rim[r][col] = v;
    }

    /** rim[lim+5..10][col] = v  (rows k = lim .. 5). */
    private static void fillToEnd(double[][] rim, int col, int lim, double v) {
        int start = Math.max(lim + 5, 0);
        for (int r = start; r < rim.length; r++) rim[r][col] = v;
    }

    // ------------------------------------------------------------------
    // Restoration: 9x3 synthetic patch of a circular edge (section 6)
    // ------------------------------------------------------------------

    private static void synthesizePatch(Context ctx, EdgePixel ep, int cx, int cy, boolean dom,
                                        int l1, int l2, int m1, int m2, int r1, int r2) {
        double radius = Math.abs(1.0 / ep.curvature);      // Infinity if curvature == 0
        if (radius > R_MAX) radius = R_MAX;
        if (radius < R_MIN) radius = R_MIN;

        final double sgn, inner, outer;
        if (ep.curvature > 0) {
            sgn = -1;
            inner = Math.min(ep.i0, ep.i1);
            outer = Math.max(ep.i0, ep.i1);
        } else {
            sgn = 1;
            inner = Math.max(ep.i0, ep.i1);
            outer = Math.min(ep.i0, ep.i1);
        }

        final double centerX = ep.x + sgn * radius * ep.nx;
        final double centerY = ep.y + sgn * radius * ep.ny;

        for (int c = -1; c <= 1; c++) {
            final int k1, k2;
            final double weight;
            if (c == -1)     { k1 = l1; k2 = l2; weight = 1.0; }
            else if (c == 0) { k1 = m1; k2 = m2; weight = WEIGHT_MIDDLE; }
            else             { k1 = r1; k2 = r2; weight = 1.0; }

            for (int k = k1; k <= k2; k++) {
                final int px = dom ? cx + c : cx + k;
                final int py = dom ? cy + k : cy + c;
                if (px < 0 || py < 0 || px >= ctx.w || py >= ctx.h) continue;

                double val = partialAreaValue(px, py, centerX, centerY, radius, inner, outer);
                ctx.acc[py][px] += val * weight;
                ctx.cnt[py][px] += weight;
            }
        }
    }

    /**
     * Intensity of pixel (px,py) covering [px-0.5,px+0.5] x [py-0.5,py+0.5] for a
     * disc of radius R: 'inner' inside, 'outer' outside, proportional to the area in the
     * edge pixel. Pixels entirely inside/outside are resolved by geometry (exact result);
     * only the pixels cut by the circle use GRID x GRID sampling (midpoints).
     */
    private static double partialAreaValue(int px, int py, double cx, double cy, double radius,
                                           double inner, double outer) {
        final double dx0 = px - cx, dy0 = py - cy;
        final double dist = Math.sqrt(dx0 * dx0 + dy0 * dy0);
        if (dist + HALF_DIAGONAL <= radius) return inner;
        if (dist - HALF_DIAGONAL >= radius) return outer;

        final double r2 = radius * radius;
        final double step = 1.0 / GRID;
        final double start = -0.5 + step / 2.0;
        int inside = 0;
        for (int i = 0; i < GRID; i++) {
            final double dy = (py + start + i * step) - cy;
            final double dy2 = dy * dy;
            for (int j = 0; j < GRID; j++) {
                final double dx = (px + start + j * step) - cx;
                if (dx * dx + dy2 <= r2) inside++;
            }
        }
        final double frac = inside / (double) (GRID * GRID);
        return outer + (inner - outer) * frac;
    }
}