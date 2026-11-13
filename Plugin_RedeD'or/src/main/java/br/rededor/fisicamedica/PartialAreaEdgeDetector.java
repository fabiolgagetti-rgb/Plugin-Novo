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

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

public class PartialAreaEdgeDetector {
	
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

    public static Result detectAndRestore(
            ImageProcessor ip,
            double threshold,
            int order
    ) {

        int rows = ip.getHeight();
        int cols = ip.getWidth();

        FloatProcessor F = (FloatProcessor) ip.convertToFloat();
        FloatProcessor G = smooth3x3(F);

        double[][] Gx = new double[rows][cols];
        double[][] Gy = new double[rows][cols];
        double[][] grad = new double[rows][cols];

        computeGradients(G, Gx, Gy, grad);

        FloatProcessor restored = new FloatProcessor(cols, rows);
        FloatProcessor counter = new FloatProcessor(cols, rows);

        List<EdgePixel> edges = new ArrayList<>();

        detectEdges(G, F, Gx, Gy, grad, threshold, order,
                restored, counter, edges, true);

        detectEdges(G, F, Gx, Gy, grad, threshold, order,
                restored, counter, edges, false);

        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                float c = counter.getf(x, y);
                if (c > 0) {
                    restored.setf(x, y, restored.getf(x, y) / c);
                } else {
                    restored.setf(x, y, G.getf(x, y));
                }
            }
        }

        Result result = new Result();
        result.restoredImage = restored;
        result.edges = edges;

        return result;
    }
    private static FloatProcessor smooth3x3(FloatProcessor ip) {
        FloatProcessor out = (FloatProcessor) ip.duplicate();
        int w = ip.getWidth();
        int h = ip.getHeight();

        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                float sum = 0f;
                for (int j = -1; j <= 1; j++) {
                    for (int i = -1; i <= 1; i++) {
                        sum += ip.getf(x + i, y + j);
                    }
                }
                out.setf(x, y, sum / 9f);
            }
        }
        return out;
    }
    private static void computeGradients(
            FloatProcessor G,
            double[][] Gx,
            double[][] Gy,
            double[][] grad
    ) {
        int w = G.getWidth();
        int h = G.getHeight();

        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                Gx[y][x] = 0.5 * (G.getf(x + 1, y) - G.getf(x - 1, y));
                Gy[y][x] = 0.5 * (G.getf(x, y + 1) - G.getf(x, y - 1));
                grad[y][x] = Math.hypot(Gx[y][x], Gy[y][x]);
            }
        }
    }
    private static void detectEdges(
            FloatProcessor G,
            FloatProcessor F,
            double[][] Gx,
            double[][] Gy,
            double[][] grad,
            double threshold,
            int order,
            FloatProcessor restored,
            FloatProcessor counter,
            List<EdgePixel> edges,
            boolean gyDominant
    ) {
        int w = G.getWidth();
        int h = G.getHeight();

        if (gyDominant) {

            for (int y = 5; y < h - 5; y++) {
                for (int x = 2; x < w - 2; x++) {
                    if (grad[y][x] <= threshold) continue;

                    double absGy = Math.abs(Gy[y][x]);
                    double absGx = Math.abs(Gx[y][x]);

                    if (absGy >= absGx &&
                        absGy >= Math.abs(Gy[y - 1][x]) &&
                        absGy > Math.abs(Gy[y + 1][x])) {

                        processEdgePixel(x, y, G, F, Gx, Gy, order, threshold, restored, counter, edges, true);
                    }
                }
            }
        } else {

            for (int y = 2; y < h - 2; y++) {
                for (int x = 5; x < w - 5; x++) {
                    if (grad[y][x] <= threshold) continue;

                    double absGx = Math.abs(Gx[y][x]);
                    double absGy = Math.abs(Gy[y][x]);
                    if (absGx > absGy &&
                        absGx >= Math.abs(Gx[y][x - 1]) &&
                        absGx > Math.abs(Gx[y][x + 1])) {

                        processEdgePixel(x, y, G, F, Gx, Gy, order, threshold, restored, counter, edges, false);
                    }
                }
            }
        }
    }
    private static void processEdgePixel(
            int x, int y,
            FloatProcessor G, FloatProcessor F,
            double[][] Gx, double[][] Gy,
            int order, double threshold,
            FloatProcessor restored, FloatProcessor counter,
            List<EdgePixel> edges, boolean gyDominant
    ) {
        double w = 0.75;
        double gx = Gx[y][x];
        double gy = Gy[y][x];

        int m;
        int l1, r2, minl1, maxr2, l2, r1, maxl2, minr1;
        int m1 = -1, m2 = 1;

        if (gx * gy >= 0) {
            m = 1; l1 = -1; r2 = 1; minl1 = -3; maxr2 = 3; l2 = 1; r1 = -1; maxl2 = 4; minr1 = -4;
        } else {
            m = -1; l1 = -1; r2 = 1; minl1 = -4; maxr2 = 4; l2 = 1; r1 = -1; maxl2 = 3; minr1 = -3;
        }

        if (gyDominant) {
            while (l1 > minl1 && Math.abs(Gy[y + l1][x - 1]) >= Math.abs(Gy[y + l1 - 1][x - 1])) l1--;
            while (l2 < maxl2 && Math.abs(Gy[y + l2][x - 1]) >= Math.abs(Gy[y + l2 + 1][x - 1])) l2++;
            while (m1 > -4    && Math.abs(Gy[y + m1][x])     >= Math.abs(Gy[y + m1 - 1][x])) m1--;
            while (m2 < 4     && Math.abs(Gy[y + m2][x])     >= Math.abs(Gy[y + m2 + 1][x])) m2++;
            while (r1 > minr1 && Math.abs(Gy[y + r1][x + 1]) >= Math.abs(Gy[y + r1 - 1][x + 1])) r1--;
            while (r2 < maxr2 && Math.abs(Gy[y + r2][x + 1]) >= Math.abs(Gy[y + r2 + 1][x + 1])) r2++;
        } else {
            while (l1 > minl1 && Math.abs(Gx[y - 1][x + l1]) >= Math.abs(Gx[y - 1][x + l1 - 1])) l1--;
            while (l2 < maxl2 && Math.abs(Gx[y - 1][x + l2]) >= Math.abs(Gx[y - 1][x + l2 + 1])) l2++;
            while (m1 > -4    && Math.abs(Gx[y][x + m1])     >= Math.abs(Gx[y][x + m1 - 1])) m1--;
            while (m2 < 4     && Math.abs(Gx[y][x + m2])     >= Math.abs(Gx[y][x + m2 + 1])) m2++;
            while (r1 > minr1 && Math.abs(Gx[y + 1][x + r1]) >= Math.abs(Gx[y + 1][x + r1 - 1])) r1--;
            while (r2 < maxr2 && Math.abs(Gx[y + 1][x + r2]) >= Math.abs(Gx[y + 1][x + r2 + 1])) r2++;
        }

        double AA, BB;
        if (gyDominant) {
            if (m > 0) { AA = (G.getf(x, y + m2) + G.getf(x + 1, y + r2)) / 2.0; BB = (G.getf(x - 1, y + l1) + G.getf(x, y + m1)) / 2.0; } 
            else       { AA = (G.getf(x - 1, y + l2) + G.getf(x, y + m2)) / 2.0; BB = (G.getf(x, y + m1) + G.getf(x + 1, y + r1)) / 2.0; }
        } else {
            if (m > 0) { AA = (G.getf(x + m2, y) + G.getf(x + r2, y + 1)) / 2.0; BB = (G.getf(x + l1, y - 1) + G.getf(x + m1, y)) / 2.0; } 
            else       { AA = (G.getf(x + l2, y - 1) + G.getf(x + m2, y)) / 2.0; BB = (G.getf(x + m1, y) + G.getf(x + r1, y + 1)) / 2.0; }
        }

        boolean uBorder = false;
        boolean dBorder = false;
        double SL = 0, SM = 0, SR = 0;
        double gradCentral = Math.abs(G.getf(x, y) / 4.0);

        if (gyDominant) {
            if (m1 > -4) { double p = Math.abs(G.getf(x, y + m1 - 2)); if (p > gradCentral && p > threshold / 2.0) uBorder = true; }
            if (m2 < 4)  { double p = Math.abs(G.getf(x, y + m2 + 2)); if (p > gradCentral && p > threshold / 2.0) dBorder = true; }
        } else {
            if (m1 > -4) { double p = Math.abs(G.getf(x + m1 - 2, y)); if (p > gradCentral && p > threshold / 2.0) uBorder = true; }
            if (m2 < 4)  { double p = Math.abs(G.getf(x + m2 + 2, y)); if (p > gradCentral && p > threshold / 2.0) dBorder = true; }
        }

        if (uBorder || dBorder) {
            if (gyDominant) {
                double[][] rimvt = new double[11][5];
                for (int dy = -5; dy <= 5; dy++) for (int dx = -2; dx <= 2; dx++) rimvt[dy + 5][dx + 2] = F.getf(x + dx, y + dy);

                if (uBorder) {
                    int p = (m > 0) ? 1 : 0;
                    if (m > 0) BB = (F.getf(x, y + m1) + F.getf(x - 1, y + l1)) / 2.0; else BB = (F.getf(x, y + m1) + F.getf(x + 1, y + r1)) / 2.0;
                    int ll = (Gy[y + l1 + p][x - 2] * gy > 0) ? (l1 + p - 1) : (l1 + p);
                    int rr = (Gy[y + r1 + 1 - p][x + 2] * gy > 0) ? (r1 - p) : (r1 + 1 - p);
                    for (int r = 0; r <= ll + 5; r++) rimvt[r][0] = BB; for (int r = 0; r <= l1 + 5; r++) rimvt[r][1] = BB;
                    for (int r = 0; r <= m1 + 5; r++) rimvt[r][2] = BB; for (int r = 0; r <= r1 + 5; r++) rimvt[r][3] = BB;
                    for (int r = 0; r <= rr + 5; r++) rimvt[r][4] = BB;
                    l1 = -3 + m; m1 = -3; r1 = -3 - m;
                }
                if (dBorder) {
                    int p = (m > 0) ? 1 : 0;
                    if (m > 0) AA = (F.getf(x, y + m2) + F.getf(x + 1, y + r2)) / 2.0; else AA = (F.getf(x, y + m2) + F.getf(x - 1, y + l2)) / 2.0;
                    int ll = (Gy[y + l2 + p - 1][x - 2] * gy > 0) ? (l2 + p) : (l2 + p - 1);
                    int rr = (Gy[y + r2 - p][x + 2] * gy > 0) ? (r2 + 1 - p) : (r2 - p);
                    for (int r = ll + 5; r <= 10; r++) rimvt[r][0] = AA; for (int r = l2 + 5; r <= 10; r++) rimvt[r][1] = AA;
                    for (int r = m2 + 5; r <= 10; r++) rimvt[r][2] = AA; for (int r = r2 + 5; r <= 10; r++) rimvt[r][3] = AA;
                    for (int r = rr + 5; r <= 10; r++) rimvt[r][4] = AA;
                    l2 = 3 + m; m2 = 3; r2 = 3 - m;
                }
                double[][] rimv2 = new double[9][3];
                for(int r = 0; r < 9; r++) for(int c = 0; c < 3; c++) {
                    double sum = 0; for(int dy = 0; dy <= 2; dy++) for(int dx = 0; dx <= 2; dx++) sum += rimvt[r + dy][c + dx];
                    rimv2[r][c] = sum / 9.0;
                }
                for (int n = l1 + 4; n <= l2 + 4; n++) SL += rimv2[n][0];
                for (int n = m1 + 4; n <= m2 + 4; n++) SM += rimv2[n][1];
                for (int n = r1 + 4; n <= r2 + 4; n++) SR += rimv2[n][2];
            } else {
                double[][] rimvt = new double[5][11];
                for (int dy = -2; dy <= 2; dy++) for (int dx = -5; dx <= 5; dx++) rimvt[dy + 2][dx + 5] = F.getf(x + dx, y + dy);
                
                if (uBorder) {
                    int p = (m > 0) ? 1 : 0;
                    if (m > 0) BB = (F.getf(x + m1, y) + F.getf(x + l1, y - 1)) / 2.0; else BB = (F.getf(x + m1, y) + F.getf(x + r1, y + 1)) / 2.0;
                    int ll = (Gx[y - 2][x + l1 + p] * gx > 0) ? (l1 + p - 1) : (l1 + p);
                    int rr = (Gx[y + 2][x + r1 + 1 - p] * gx > 0) ? (r1 - p) : (r1 + 1 - p);
                    for (int c = 0; c <= ll + 5; c++) rimvt[0][c] = BB; for (int c = 0; c <= l1 + 5; c++) rimvt[1][c] = BB;
                    for (int c = 0; c <= m1 + 5; c++) rimvt[2][c] = BB; for (int c = 0; c <= r1 + 5; c++) rimvt[3][c] = BB;
                    for (int c = 0; c <= rr + 5; c++) rimvt[4][c] = BB;
                    l1 = -3 + m; m1 = -3; r1 = -3 - m;
                }
                if (dBorder) {
                    int p = (m > 0) ? 1 : 0;
                    if (m > 0) AA = (F.getf(x + m2, y) + F.getf(x + r2, y + 1)) / 2.0; else AA = (F.getf(x + m2, y) + F.getf(x + l2, y - 1)) / 2.0;
                    int ll = (Gx[y - 2][x + l2 + p - 1] * gx > 0) ? (l2 + p) : (l2 + p - 1);
                    int rr = (Gx[y + 2][x + r2 - p] * gx > 0) ? (r2 + 1 - p) : (r2 - p);
                    for (int c = ll + 5; c <= 10; c++) rimvt[0][c] = AA; for (int c = l2 + 5; c <= 10; c++) rimvt[1][c] = AA;
                    for (int c = m2 + 5; c <= 10; c++) rimvt[2][c] = AA; for (int c = r2 + 5; c <= 10; c++) rimvt[3][c] = AA;
                    for (int c = rr + 5; c <= 10; c++) rimvt[4][c] = AA;
                    l2 = 3 + m; m2 = 3; r2 = 3 - m;
                }
                double[][] rimv2 = new double[3][9];
                for(int r = 0; r < 3; r++) for(int c = 0; c < 9; c++) {
                    double sum = 0; for(int dy = 0; dy <= 2; dy++) for(int dx = 0; dx <= 2; dx++) sum += rimvt[r + dy][c + dx];
                    rimv2[r][c] = sum / 9.0;
                }
                for (int n = l1 + 4; n <= l2 + 4; n++) SL += rimv2[0][n];
                for (int n = m1 + 4; n <= m2 + 4; n++) SM += rimv2[1][n];
                for (int n = r1 + 4; n <= r2 + 4; n++) SR += rimv2[2][n];
            }
        } else {
            if (gyDominant) {
                for (int n = l1; n <= l2; n++) SL += G.getf(x - 1, y + n);
                for (int n = m1; n <= m2; n++) SM += G.getf(x, y + n);
                for (int n = r1; n <= r2; n++) SR += G.getf(x + 1, y + n);
            } else {
                for (int n = l1; n <= l2; n++) SL += G.getf(x + n, y - 1);
                for (int n = m1; n <= m2; n++) SM += G.getf(x + n, y);
                for (int n = r1; n <= r2; n++) SR += G.getf(x + n, y + 1);
            }
        }

        double den = 2 * (AA - BB);
        if (Math.abs(den) < 1e-6) return;

        double c = 0;
        if (order == 2) c = (SL + SR - 2 * SM + AA * (2 * m2 - l2 - r2) - BB * (2 * m1 - l1 - r1)) / den;
        
        double a = (2 * SM - AA * (1 + 2 * m2) - BB * (1 - 2 * m1)) / den - w * c;
        if (Math.abs(a) > 1.0) return;
        
        double b = (SR - SL + AA * (l2 - r2) - BB * (l1 - r1)) / den;

        double s = Math.signum(AA - BB);
        double nx = gyDominant ? (s / Math.sqrt(1 + b * b) * b) : (s / Math.sqrt(1 + b * b));
        double ny = gyDominant ? (s / Math.sqrt(1 + b * b)) : (s / Math.sqrt(1 + b * b) * b);
        double curvature = 2 * c / Math.pow(1 + b * b, 1.5);
        if (gyDominant && gy < 0) curvature = -curvature;
        if (!gyDominant && gx < 0) curvature = -curvature;

        EdgePixel ep = new EdgePixel();
        ep.x = gyDominant ? x : x - a;
        ep.y = gyDominant ? y - a : y;
        ep.nx = nx; ep.ny = ny;
        ep.curvature = curvature;
        ep.i0 = Math.min(AA, BB); ep.i1 = Math.max(AA, BB);
        edges.add(ep);

        synthesizeLocalPatch(ep, x, y, l1, l2, m1, m2, r1, r2, restored, counter, gyDominant);
    }
    private static void synthesizeLocalPatch(
            EdgePixel ep, int cx, int cy,
            int l1, int l2, int m1, int m2, int r1, int r2,
            FloatProcessor restored,
            FloatProcessor counter,
            boolean gyDominant
    ) {
        double R = Math.abs(1.0 / ep.curvature);
        if (R > 10000.0) R = 10000.0;
        if (R < 4.5) R = 4.5;

        double s;
        double innerIntensity, outerIntensity;
        if (ep.curvature > 0) {
            s = -1;
            innerIntensity = Math.min(ep.i0, ep.i1);
            outerIntensity = Math.max(ep.i0, ep.i1);
        } else {
            s = 1;
            innerIntensity = Math.max(ep.i0, ep.i1);
            outerIntensity = Math.min(ep.i0, ep.i1);
        }

        double centerX = ep.x + s * R * ep.nx;
        double centerY = ep.y + s * R * ep.ny;

        int gridResol = 50;
        double step = 1.0 / gridResol;
        double startOffset = -0.5 + step / 2.0;

        int w = restored.getWidth();
        int h = restored.getHeight();

        if (gyDominant) {
            for (int dx = -1; dx <= 1; dx++) {
                int px = cx + dx;
                int startY, endY;
                double weight;

                if (dx == -1) { startY = l1; endY = l2; weight = 1.0; }
                else if (dx == 0) { startY = m1; endY = m2; weight = 100.0; }
                else { startY = r1; endY = r2; weight = 1.0; }

                for (int dy = startY; dy <= endY; dy++) {
                    int py = cy + dy;
                    if (px < 0 || py < 0 || px >= w || py >= h) continue;

                    double val = calculatePartialArea(px, py, centerX, centerY, R, innerIntensity, outerIntensity, gridResol, step, startOffset);

                    restored.setf(px, py, restored.getf(px, py) + (float) (val * weight));
                    counter.setf(px, py, counter.getf(px, py) + (float) weight);
                }
            }
        } else {
            for (int dy = -1; dy <= 1; dy++) {
                int py = cy + dy;
                int startX, endX;
                double weight;

                if (dy == -1) { startX = l1; endX = l2; weight = 1.0; }
                else if (dy == 0) { startX = m1; endX = m2; weight = 100.0; }
                else { startX = r1; endX = r2; weight = 1.0; }

                for (int dx = startX; dx <= endX; dx++) {
                    int px = cx + dx;
                    if (px < 0 || py < 0 || px >= w || py >= h) continue;

                    double val = calculatePartialArea(px, py, centerX, centerY, R, innerIntensity, outerIntensity, gridResol, step, startOffset);

                    restored.setf(px, py, restored.getf(px, py) + (float) (val * weight));
                    counter.setf(px, py, counter.getf(px, py) + (float) weight);
                }
            }
        }
    }
    private static double calculatePartialArea(
            int px, int py,
            double cx, double cy, double R,
            double inInt, double outInt,
            int resol, double step, double startOffset
    ) {
        double sum = 0;
        double R2 = R * R;
        
        for (int i = 0; i < resol; i++) {
            double sy = py + startOffset + i * step;
            double dy = sy - cy;
            double dy2 = dy * dy;
            
            for (int j = 0; j < resol; j++) {
                double sx = px + startOffset + j * step;
                double dx = sx - cx;
                
                if (dx * dx + dy2 <= R2) {
                    sum += inInt;
                } else {
                    sum += outInt;
                }
            }
        }
        return sum / (resol * resol);
    }
    public Point2D refine(ImageProcessor ip,
            double x0, double y0,
            double nx, double ny) {

double[] profile = sampleProfile(ip, x0, y0, nx, ny);

WindowBounds bounds = computeDynamicWindow(profile);

return fitPAE(profile, bounds, x0, y0, nx, ny);
}
    private double[] sampleProfile(ImageProcessor ip,
            double x0, double y0,
            double nx, double ny) {

int Smax = 6;
int length = 2 * Smax + 1;

double[] profile = new double[length];

for (int i = -Smax; i <= Smax; i++) {

double x = x0 + i * nx;
double y = y0 + i * ny;

profile[i + Smax] = ip.getInterpolatedValue(x, y);
}

return profile;
}
    private WindowBounds computeDynamicWindow(double[] profile) {

        int n = profile.length;

        double[] grad = new double[n - 1];

        for (int i = 0; i < n - 1; i++) {
            grad[i] = profile[i + 1] - profile[i];
        }

        int idxMax = 0;
        double maxVal = 0;

        for (int i = 0; i < grad.length; i++) {
            double val = Math.abs(grad[i]);
            if (val > maxVal) {
                maxVal = val;
                idxMax = i;
            }
        }

        double alpha = 0.2;
        double threshold = alpha * maxVal;

        int left = idxMax;
        int right = idxMax;

        while (left > 0 && Math.abs(grad[left]) > threshold) {
            left--;
        }
        while (right < grad.length - 1 &&
               Math.abs(grad[right]) > threshold) {
            right++;
        }

        return new WindowBounds(left, right + 1);
    }
    private Point2D fitPAE(double[] profile,
            WindowBounds bounds,
            double x0, double y0,
            double nx, double ny) {

int start = bounds.start;
int end = bounds.end;

double delta = solveSubpixelOffset(profile, start, end);

double xRef = x0 + delta * nx;
double yRef = y0 + delta * ny;

return new Point2D.Double(xRef, yRef);
}
    private static class WindowBounds {
        int start;
        int end;

        WindowBounds(int s, int e) {
            this.start = s;
            this.end = e;
        }
    }

    private double solveSubpixelOffset(double[] profile,
            int start,
            int end) {

int n = profile.length;
double[] grad = new double[n - 1];

for (int i = 0; i < n - 1; i++) {
grad[i] = profile[i + 1] - profile[i];
}

int idxMax = start;
double maxVal = Math.abs(grad[start]);

for (int i = start; i < end; i++) {
double val = Math.abs(grad[i]);
if (val > maxVal) {
maxVal = val;
idxMax = i;
}
}

if (idxMax <= 0 || idxMax >= grad.length - 1) {
return 0.0;
}

double g1 = grad[idxMax - 1];
double g2 = grad[idxMax];
double g3 = grad[idxMax + 1];

double denom = (g1 - 2*g2 + g3);

if (Math.abs(denom) < 1e-10) {
return 0.0;
}

double delta = 0.5 * (g1 - g3) / denom;

int centerIndex = profile.length / 2;

double offset = (idxMax + delta) - centerIndex;

return offset;
}
    public static Result detectAndRestoreIterative(
            ImageProcessor ip,
            double threshold,
            int order,
            int iterations
    ) {
        FloatProcessor currentImage = (FloatProcessor) ip.convertToFloat();
        Result finalResult = null;

        for (int iter = 0; iter < iterations; iter++) {
            
            finalResult = detectAndRestore(currentImage, threshold, order);
            
            currentImage = finalResult.restoredImage;
        }

        return finalResult;
    }

}
