package br.rededor.fisicamedica;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jtransforms.fft.DoubleFFT_1D;

import br.rededor.fisicamedica.Segmenter.DetectorResult;
import ij.ImageStack;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.process.FloatPolygon;
import ij.process.ImageProcessor;

/**
 * Measures the TTF (Task Transfer Function) from detected object edges.
 *
 * For each insert in a DetectorResult it samples the ESF (edge spread function)
 * along the edge normal with sub-pixel Lanczos interpolation, averages the profiles,
 * takes the finite-difference LSF, windows it, and FFTs to the TTF. A contrast-match
 * gate selects the profiles belonging to the target edge (rejecting ones corrupted
 * by internal structures). Produces TTF, LSF, ESF and frequency arrays per insert.
 */
public class TtfCalc {
    static int phantomWidth = 0;
    static int phantomHeight = 0;

    // --- ESF/TTF tunables (named constants; validated defaults) ---
    /** Half-window (points) for smoothing the per-point PAE normals along the edge. */
    private static final int PAE_NORMAL_SMOOTH_HALF = 3;
    /** Absolute HU floor of the contrast-matching tolerance: rejects ESF profiles
     *  corrupted by internal structures (e.g. marrow inside a vertebra). */
    private static final double CONTRAST_TOL_HU  = 15.0;
    /** Relative part of the contrast-matching tolerance (fraction of the expected
     *  contrast); bridges the gap between the segmenter's and the ESF endpoint
     *  contrast on edge-enhancing kernels without widening it for low-contrast edges. */
    private static final double CONTRAST_TOL_REL = 0.20;

    public static class mtfResult {
        public Map<Integer, double[]> ttfs = new HashMap<>();
        public Map<Integer, double[]> freqs = new HashMap<>();
        public Map<Integer, double[]> lsfs = new HashMap<>();
        public Map<Integer, double[]> esfs = new HashMap<>();
        public Map<Integer, Double> contrasts = new HashMap<>();
        public Map<Integer, Double> I0 = new HashMap<>();
        public Map<Integer, Double> I1 = new HashMap<>();
        public Map<Integer, Point> positions = new HashMap<>();
    }
    
    public static mtfResult analisarStackComInserts(ImageStack stack, Calibration cal, DetectorResult dR, int fftPoints) {
        int nSlices = stack.getSize();
        List<Point> points = dR.Pos;
        mtfResult result = new mtfResult();
        
        for (int i = 0; i < points.size(); i++) {
            if (dR.rois.get(i) == null) continue;
            
            boolean invert = false;
            if (dR.HU.get(i) < dR.Threshold.get(i)) invert = true;
            
            double targetContrast = 0.0;
            double I0 = 0.0;
            double I1 = 0.0;

            if (!dR.modeContrast.isEmpty() && i < dR.modeContrast.size() && !dR.modeContrast.get(i).isEmpty()) {
                // The segmenter may report more than one contrast population for a
                // structure. For a thin high-contrast shell around a lower-contrast
                // core (e.g. a vertebra ring around bone marrow), profiles that
                // overshoot the thin shell read the core as the object side, creating
                // a spurious LOWER-contrast population (background->marrow) alongside
                // the true outer step (background->bone). The old code took get(0) =
                // the most-populous bin, which under noise flips to the spurious core
                // edge and corrupts the TTF. Select the LARGEST contrast instead: the
                // true external step is always the biggest, so this robustly targets
                // the outer silhouette and ignores thin-shell/core sub-edges.
                List<Double> contrastsForInsert = dR.modeContrast.get(i);
                int sel = 0;
                for (int ci = 1; ci < contrastsForInsert.size(); ci++) {
                    if (contrastsForInsert.get(ci) > contrastsForInsert.get(sel)) sel = ci;
                }
                targetContrast = contrastsForInsert.get(sel);
                if (i < dR.meanI0.size() && sel < dR.meanI0.get(i).size()) I0 = dR.meanI0.get(i).get(sel);
                if (i < dR.meanI1.size() && sel < dR.meanI1.get(i).size()) I1 = dR.meanI1.get(i).get(sel);
            }
            
            List<Double> singleExpectedContrastList = new ArrayList<>();
            singleExpectedContrastList.add(targetContrast);
            
            List<double[]> esfProfilesAccumulated = new ArrayList<>();
            
            for (int j = 1; j <= nSlices; j++) {
                ImageProcessor ip = stack.getProcessor(j);
                Roi roi = dR.rois.get(i).get(j - 1);
                FloatPolygon boundary = roi.getFloatPolygon();

                double[][] pointNormals = null;
                if (dR.edgeNormals != null && i < dR.edgeNormals.size()
                        && dR.edgeNormals.get(i) != null && (j - 1) < dR.edgeNormals.get(i).size()) {
                    pointNormals = dR.edgeNormals.get(i).get(j - 1);
                }

                List<List<double[]>> groupedEsfs = calculateESF(cal, ip, boundary, (int) (10 / cal.pixelWidth), invert, singleExpectedContrastList, pointNormals);
                
                if (groupedEsfs != null && !groupedEsfs.isEmpty() && groupedEsfs.get(0) != null) {
                    esfProfilesAccumulated.addAll(groupedEsfs.get(0));
                }
            }
            
            if (esfProfilesAccumulated.isEmpty()) continue; 
            
            double[] avgEsf = CurveAverage.averageEsfs(esfProfilesAccumulated);
            if (avgEsf == null) continue;

            double[] lsf = derivate(avgEsf);
            double[] mtf = calculateTtf(lsf, 2 * fftPoints);

            double samplingStepPx = 0.5; 
            double dx_mm = samplingStepPx * cal.pixelWidth; 
            double fNyquist = 1.0 / (2.0 * dx_mm); 
            int length = mtf.length;
             
            double[] frequency = new double[length];
            for (int i1 = 0; i1 < length; i1++) {
                frequency[i1] = (fNyquist * i1) / (length - 1);
            }
            
            int insertId = i;
            
            result.esfs.put(insertId, avgEsf);
            result.lsfs.put(insertId, lsf);
            result.ttfs.put(insertId, mtf);
            result.freqs.put(insertId, frequency);
            result.contrasts.put(insertId, targetContrast);
            result.I0.put(insertId, I0);
            result.I1.put(insertId, I1);

            result.positions.put(insertId, points.get(i));
        }
        return result;
    }

    public static List<List<double[]>> calculateESF(Calibration cal, ImageProcessor ip, FloatPolygon boundary, int profileLength, boolean invert, List<Double> expectedContrasts) {
        return calculateESF(cal, ip, boundary, profileLength, invert, expectedContrasts, null);
    }

    /**
     * ESF sampling with an optional per-point normal array.
     *
     * When pointNormals is non-null and aligned 1:1 with the boundary polygon, each
     * profile is sampled along that point's own analytic (PAE) normal. This is
     * independent of polygon topology, so gaps in the polygon cannot skew the
     * sampling direction. When normals are not supplied, the profile direction is a
     * least-squares line fit over neighbouring polygon points (used by callers that
     * pass a plain boundary, e.g. a known analytic edge).
     */
    public static List<List<double[]>> calculateESF(Calibration cal, ImageProcessor ip, FloatPolygon boundary, int profileLength, boolean invert, List<Double> expectedContrasts, double[][] pointNormals) {
        if (boundary == null) throw new IllegalArgumentException("null ROI");
        // Sample each profile along its point's own analytic (PAE) normal when those
        // normals are supplied (the edge detector provides one per boundary point).
        // This is independent of polygon topology, so gaps in the polygon cannot skew
        // the sampling direction, unlike a least-squares fit over neighbouring points.
        boolean usePaeNormals = pointNormals != null && pointNormals.length == boundary.npoints;

        // Smooth the PAE normals along the edge ordering. PAE normals are local but
        // per-point noisy; averaging each with its neighbours (a +/- window, in polygon
        // index order) reduces that noise. It stays hole-immune: a missing point just
        // means one fewer term in the average. Half-window = PAE_NORMAL_SMOOTH_HALF
        // (0 would mean raw, unsmoothed normals).
        double[][] normals = pointNormals;
        if (usePaeNormals) {
            int halfWin = PAE_NORMAL_SMOOTH_HALF;
            if (halfWin > 0) {
                int n = pointNormals.length;
                normals = new double[n][2];
                for (int p = 0; p < n; p++) {
                    double sx = 0, sy = 0;
                    for (int w = -halfWin; w <= halfWin; w++) {
                        int q = ((p + w) % n + n) % n;      // wrap around closed contour
                        sx += pointNormals[q][0];
                        sy += pointNormals[q][1];
                    }
                    double len = Math.hypot(sx, sy);
                    if (len == 0) { normals[p][0] = pointNormals[p][0]; normals[p][1] = pointNormals[p][1]; }
                    else { normals[p][0] = sx / len; normals[p][1] = sy / len; }
                }
            }
        }
        profileLength *= 2;
        if (profileLength % 2 == 0) profileLength++;

        int half = profileLength / 2;
        double step = 0.5;
        // Contrast-matching tolerance. Kept as an ABSOLUTE HU floor (rejects profiles
        // corrupted by internal structures, e.g. bone marrow inside a spine insert,
        // which shift the endpoint contrast) PLUS a RELATIVE band that scales with the
        // expected contrast. The relative term bridges the systematic gap between the
        // segmenter's contrast estimate and the ESF endpoint contrast on edge-enhancing
        // kernels, without widening the window for low-contrast structures.
        double toleranceHU = CONTRAST_TOL_HU;
        double relTol      = CONTRAST_TOL_REL;
        
        List<List<double[]>> groupedProfiles = new ArrayList<>();
        boolean checkContrast = expectedContrasts != null && !expectedContrasts.isEmpty() && expectedContrasts.get(0) > 0;
        
        if (!checkContrast) {
            groupedProfiles.add(new ArrayList<>()); 
        } else {
            for (int i = 0; i < expectedContrasts.size(); i++) {
                groupedProfiles.add(new ArrayList<>());
            }
        }

        for (int i = 0; i < boundary.npoints - 1; i++) {
            float x0 = boundary.xpoints[i];
            float y0 = boundary.ypoints[i];

            double nx, ny;

            if (usePaeNormals) {
                // Use this point's own analytic PAE normal; independent of polygon
                // neighbours, so holes in the polygon cannot skew the sampling direction.
                double pnx = normals[i][0];
                double pny = normals[i][1];
                double plen = Math.hypot(pnx, pny);
                if (plen == 0) continue;   // degenerate normal; skip (one fewer profile)
                nx = pnx / plen;
                ny = pny / plen;
                if (invert) { nx = -nx; ny = -ny; }
                double[] profile = new double[profileLength];
                for (int j = -half; j <= half; j++) {
                    double x = x0 + j * step * nx;
                    double y = y0 + j * step * ny;
                    double val = getLanczosInterpolatedPixel(ip, x, y);
                    profile[j + half] = cal.getCValue(val);
                }
                addProfileWithContrastCheck(profile, profileLength, checkContrast,
                        expectedContrasts, toleranceHU, relTol, groupedProfiles);
                continue;
            }

            double searchRadiusMM = 5.0;
            double searchRadiusPx = searchRadiusMM / cal.pixelWidth;

            int startIdx = i;
            double distBack = 0;
            while (startIdx > 0 && distBack < searchRadiusPx) {
                distBack += Math.hypot(
                    boundary.xpoints[startIdx] - boundary.xpoints[startIdx - 1], 
                    boundary.ypoints[startIdx] - boundary.ypoints[startIdx - 1]
                );
                startIdx--;
            }

            int endIdx = i;
            double distFwd = 0;
            while (endIdx < boundary.npoints - 1 && distFwd < searchRadiusPx) {
                distFwd += Math.hypot(
                    boundary.xpoints[endIdx + 1] - boundary.xpoints[endIdx], 
                    boundary.ypoints[endIdx + 1] - boundary.ypoints[endIdx]
                );
                endIdx++;
            }
           
            if (distBack < searchRadiusPx || distFwd < searchRadiusPx) {
                continue;
            }

            int count = endIdx - startIdx + 1;

            double sumX = 0, sumY = 0;
            for (int w = startIdx; w <= endIdx; w++) {
                sumX += boundary.xpoints[w];
                sumY += boundary.ypoints[w];
            }
            double meanX = sumX / count;
            double meanY = sumY / count;

            double Sxx = 0, Syy = 0, Sxy = 0;
            for (int w = startIdx; w <= endIdx; w++) {
                double diffX = boundary.xpoints[w] - meanX;
                double diffY = boundary.ypoints[w] - meanY;
                Sxx += diffX * diffX;
                Syy += diffY * diffY;
                Sxy += diffX * diffY;
            }

            double dx, dy;
            if (Sxx > Syy) {
                double slope = Sxy / Sxx; 
                dx = 1.0;
                dy = slope;
            } else {
                if (Syy == 0) continue;
                double slope = Sxy / Syy; 
                dx = slope;
                dy = 1.0;
            }

            double originalDx = boundary.xpoints[endIdx] - boundary.xpoints[startIdx];
            double originalDy = boundary.ypoints[endIdx] - boundary.ypoints[startIdx];

            if ((dx * originalDx + dy * originalDy) < 0) {
                dx = -dx;
                dy = -dy;
            }
            double length = Math.hypot(dx, dy);
            if (length == 0) continue;

            dx /= length;
            dy /= length;
            
            nx = -dy;
            ny = dx;

            if (invert) { nx = -nx; ny = -ny; }

            double[] profile = new double[profileLength];

            for (int j = -half; j <= half; j++) {
                double x = x0 + j * step * nx;
                double y = y0 + j * step * ny;
                double val = getLanczosInterpolatedPixel(ip, x, y);
                profile[j + half] = cal.getCValue(val);
            }
            
            if (checkContrast) { 
                int avgPts = 3;
                double valBgd = 0;
                double valObject = 0;
                for(int p = 0; p < avgPts; p++) {
                    // profile[] is already calibrated (cal.getCValue applied when sampled);
                    // do NOT calibrate a second time.
                    valBgd += profile[p];
                    valObject += profile[profileLength - 1 - p];
                }
                valBgd /= avgPts;
                valObject /= avgPts;
                
                double edgeContrast = Math.abs(valObject - valBgd);
                
                int bestMatchIdx = -1;
                double minDiff = Double.MAX_VALUE;
                
                for (int c = 0; c < expectedContrasts.size(); c++) {
                    double expected = expectedContrasts.get(c);
                    double diff = Math.abs(edgeContrast - expected);
                    double tol = Math.max(toleranceHU, relTol * Math.abs(expected));
                    if (diff <= tol && diff < minDiff) {
                        minDiff = diff;
                        bestMatchIdx = c;
                    }
                }

                if (bestMatchIdx != -1) {
                    groupedProfiles.get(bestMatchIdx).add(profile);
                }
            } else {
                groupedProfiles.get(0).add(profile);
            }
        }
        
        return groupedProfiles;
    }

    /**
     * Applies the contrast-match gate and files a profile into the right group.
     * Shared by the neighbour-fit and PAE-normal paths so both enforce the same
     * internal-structure rejection (absolute HU floor + relative band).
     */
    private static void addProfileWithContrastCheck(double[] profile, int profileLength,
            boolean checkContrast, List<Double> expectedContrasts,
            double toleranceHU, double relTol, List<List<double[]>> groupedProfiles) {
        if (checkContrast) {
            int avgPts = 3;
            double valBgd = 0, valObject = 0;
            for (int p = 0; p < avgPts; p++) {
                valBgd += profile[p];
                valObject += profile[profileLength - 1 - p];
            }
            valBgd /= avgPts;
            valObject /= avgPts;
            double edgeContrast = Math.abs(valObject - valBgd);

            int bestMatchIdx = -1;
            double minDiff = Double.MAX_VALUE;
            for (int c = 0; c < expectedContrasts.size(); c++) {
                double expected = expectedContrasts.get(c);
                double diff = Math.abs(edgeContrast - expected);
                double tol = Math.max(toleranceHU, relTol * Math.abs(expected));
                if (diff <= tol && diff < minDiff) {
                    minDiff = diff;
                    bestMatchIdx = c;
                }
            }
            if (bestMatchIdx != -1) groupedProfiles.get(bestMatchIdx).add(profile);
        } else {
            groupedProfiles.get(0).add(profile);
        }
    }

    public static double getLanczosInterpolatedPixel(ImageProcessor ip, double x, double y) {
        int a = 3; 
        
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        
        double sum = 0.0;
        double weightSum = 0.0;
        int width = ip.getWidth();
        int height = ip.getHeight();
        
        for (int i = x0 - a + 1; i <= x0 + a; i++) {
            for (int j = y0 - a + 1; j <= y0 + a; j++) {
                
                double dx = x - i;
                double dy = y - j;
                
                double weight = lanczos(dx, a) * lanczos(dy, a);
                
                int px = Math.max(0, Math.min(i, width - 1));
                int py = Math.max(0, Math.min(j, height - 1));
                
                double val = ip.getf(px, py);
                
                sum += val * weight;
                weightSum += weight;
            }
        }
        
        if (weightSum == 0) return 0;
        return sum / weightSum;
    }

    private static double lanczos(double x, int a) {
        if (x == 0.0) return 1.0;
        if (Math.abs(x) >= a) return 0.0;
        
        double piX = Math.PI * x;
        return (a * Math.sin(piX) * Math.sin(piX / a)) / (piX * piX);
    }
    
    public static double[] calculateTtf(double[] lsf, int fftPoints) {
        int sizeLsf = lsf.length;
        int n = Math.max(fftPoints, sizeLsf);
        
        int fftHalf = n / 2;
        double[] mtfSum = new double[fftHalf + 1];
        DoubleFFT_1D fft = new DoubleFFT_1D(n);
                
        double[] paddedLsf = new double[n];
        int padLeft = (n - sizeLsf) / 2;
        
        System.arraycopy(lsf, 0, paddedLsf, padLeft, sizeLsf);
        
        double[] fftData = new double[2 * n];
        System.arraycopy(paddedLsf, 0, fftData, 0, n);
        fft.realForwardFull(fftData);

        for (int k = 0; k <= fftHalf; k++) {
            double re = fftData[2 * k];
            double im = fftData[2 * k + 1];
            mtfSum[k] += Math.sqrt(re * re + im * im);
        }
        
        double f0 = mtfSum[0];
        if (!(f0 > 0)) {
            f0 = 1.0;
        }
        for (int k = 0; k <= fftHalf; k++) {
            mtfSum[k] /= f0;
        }
        
        return mtfSum;
    }

    /**
     * Finite-difference derivative of the ESF to obtain the LSF, then apply a Tukey
     * window to suppress noise in the LSF tails before the FFT.
     */
    public static double[] derivate(double[] esf) {
        int n = esf.length;
        double[] lsf = new double[n];

        for (int i = 0; i < n - 1; i++) {
            lsf[i] = esf[i + 1] - esf[i];
        }
        lsf[n - 1] = 0;

        return windowLsf(lsf);
    }

    /**
     * Tukey window whose flat-top width is derived from the central-peak FWHM
     * (alpha = 1 - 4*FWHM/N): the sharper the LSF, the wider the taper that
     * suppresses the (noise-dominated) tails.
     */
    private static double[] windowLsf(double[] lsf) {
        int sizeLsf = lsf.length;
        double max = 0;
        for (int k = 0; k < lsf.length; k++) {
            if (Math.abs(lsf[k]) > max) max = Math.abs(lsf[k]);
        }

        int left = 0, right = 0;
        boolean peak = false;
        for (int i = 0; i < sizeLsf; i++) {
            if (Math.abs(lsf[i]) > max / 2 && !peak) { left = i; peak = true; }
            if (Math.abs(lsf[i]) < max / 2 && peak)  { right = i; break; }
        }

        double FWHM = right - left;
        double alpha = 1 - (FWHM * 4 / sizeLsf);
        double steepness = 5;
        return applyTukey(lsf, alpha, steepness);
    }
    /** Asymmetric-power Tukey window applied symmetrically over the LSF support. */
    private static double[] applyTukey(double[] lsf, double alpha, double steepness) {
        int sizeLsf = lsf.length;
        double[] windowedLsf = new double[sizeLsf];
        if (alpha <= 0) {
            System.arraycopy(lsf, 0, windowedLsf, 0, sizeLsf);
        } else if (alpha >= 1.0) {
            for (int i = 0; i < sizeLsf; i++) {
                double x = (double) i / (sizeLsf - 1);
                double w = 0.5 * (1 - Math.cos(2 * Math.PI * x));
                windowedLsf[i] = lsf[i] * w;
            }
        } else {
            double halfAlpha = alpha / 2.0;
            for (int i = 0; i < sizeLsf; i++) {
                double x = (double) i / (sizeLsf - 1);
                double w;
                if (x < halfAlpha) {
                    double t = Math.pow(x / halfAlpha, steepness);
                    w = 0.5 * (1 + Math.cos(Math.PI * (t - 1)));
                } else if (x <= 1.0 - halfAlpha) {
                    w = 1.0;
                } else {
                    double t = Math.pow((1.0 - x) / halfAlpha, steepness);
                    w = 0.5 * (1 + Math.cos(Math.PI * (t - 1)));
                }
                windowedLsf[i] = lsf[i] * w;
            }
        }
        return windowedLsf;
    }
}
