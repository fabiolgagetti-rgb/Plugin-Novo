package br.rededor.fisicamedica;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.process.ImageProcessor;

import java.util.ArrayList;
import java.util.List;

import br.rededor.fisicamedica.Segmenter.DetectorResult;
import br.rededor.fisicamedica.TtfCalc.mtfResult;

public class ESFValidation {

    public static void main(String[] args) {
        
        // RMSE_raw/Bias_raw: not aligned (includes the registration error).
        // RMSE_align: shape/intensity fidelity only, after removing the
        //             lateral shift.
        // Offset_px:  edge positioning bias (detected vs analytic).
        // FailReasons: breakdown of failures by reason (see EdgeDiagnostics).
        EdgeDiagnostics.enable();
        System.out.println("Kernel\tNoise(SD)\tRMSE_raw(HU)\tBias_raw(HU)\tRMSE_align(HU)\tOffset(px)\tFailures\tFailReasons");

        int nHomogeneousSlices = 0;
        int nInsertSlices = 1;
        int matrix = 512;
        double fov = 250.0;
        double bgHU = -1000.0;
        double objectMeanHU = 0.0; 
        double diameterMM = 200.0; 
        double trendAmpHU = 0.0;
        double[] insertHU = {100}; // Insert contrast
        double insertRadiusMM = 12.5; 
        double insertDistanceMM = 0.0; 
        
        double pixelSize = fov / matrix; 
        
        Calibration cal = new Calibration();
        cal.pixelWidth = pixelSize;
        cal.pixelHeight = pixelSize;
        cal.setUnit("mm");
        
        int roiSize = (int) (insertRadiusMM * 3 / pixelSize);
        double gtCenterPx = matrix / 2.0; 
        int roiX = (int) gtCenterPx - (roiSize / 2);
        int roiY = (int) gtCenterPx - (roiSize / 2);
        Roi userRoi = new Roi(roiX, roiY, roiSize, roiSize);
        
        String[] kernels = {"SOFT", "Lung"}; 
        int[] noiseLevelsSD = {1, 10, 30, 45}; 
        int numMeasurements = 30;
        int fftPoints = 64; 
        
        // Exact ESF array length as computed in TtfCalc
        int profileLength = (int) (10 / cal.pixelWidth);
        profileLength *= 2;
        if (profileLength % 2 == 0) profileLength++;

        for (String kernel : kernels) {
            
            // 1. GENERATE THE ABSOLUTE GROUND-TRUTH ESF (directly from the 1D matrix)
            double[] esfIdeal = null;
            try {
                PhantomImageCreator creator = new PhantomImageCreator();
                ImagePlus impZero = creator.criarImagemCTPhantom(
                    nHomogeneousSlices, nInsertSlices, matrix, fov, bgHU, objectMeanHU, 
                    diameterMM, trendAmpHU, insertHU, insertRadiusMM, insertDistanceMM, 
                    kernel, 0.0 // ZERO noise
                );
                
                ImageProcessor ipZero = impZero.getStack().getProcessor(1);
                
                // Extract the ESF exactly at the 12 o'clock position
                esfIdeal = generateIdealEsfDirect(ipZero, gtCenterPx, insertRadiusMM / pixelSize, profileLength, cal);
                
            } catch (Exception e) {
                System.out.println("Error generating absolute ground truth for " + kernel);
                continue;
            }

            // 2. TEST WITH NOISE AND THE FULL PIPELINE
            for (int sd : noiseLevelsSD) {
                double variance = sd * sd; 
                
                List<Double> rmseList = new ArrayList<>();
                List<Double> biasList = new ArrayList<>();
                List<Double> rmseAlignList = new ArrayList<>();
                List<Double> offsetList = new ArrayList<>();
                int failures = 0;

                EdgeDiagnostics.reset();  // reset the per-reason counters for this condition

                for (int i = 0; i < numMeasurements; i++) {
                    try {
                        PhantomImageCreator creator = new PhantomImageCreator();
                        ImagePlus imp = creator.criarImagemCTPhantom(
                            nHomogeneousSlices, nInsertSlices, matrix, fov, bgHU, objectMeanHU, 
                            diameterMM, trendAmpHU, insertHU, insertRadiusMM, insertDistanceMM, 
                            kernel, variance
                        );
                        
                        DetectorResult dR = Segmenter.edgeFinder(imp.getStack(), cal, userRoi, false, true);
                        if (dR == null) {
                            failures++; i--; continue;
                        }
                        
                        mtfResult mtfRes = TtfCalc.analisarStackComInserts(imp.getStack(), cal, dR, fftPoints);
                        if (mtfRes == null || mtfRes.esfs == null || mtfRes.esfs.isEmpty()) {
                            failures++; continue;
                        }

                        double[] measuredEsf = mtfRes.esfs.values().iterator().next();
                        
                        // Validate the measured ESF against the absolute analytic ESF
                        double[] metrics = esfError(measuredEsf, esfIdeal);
                        if (!Double.isNaN(metrics[0])) {
                            rmseList.add(metrics[0]);      // RMSE_raw
                            biasList.add(metrics[1]);      // Bias_raw
                            if (!Double.isNaN(metrics[2])) rmseAlignList.add(metrics[2]); // RMSE_align
                            if (!Double.isNaN(metrics[3])) offsetList.add(metrics[3]);    // Offset_px
                        } else {
                            failures++;
                        }
                        
                    } catch (Exception e) {
                        failures++;
                    }
                }
                
                double meanRmse = calculateMean(rmseList);
                double meanBias = calculateMean(biasList);
                double meanRmseAlign = calculateMean(rmseAlignList);
                double meanOffset = calculateMean(offsetList);
                System.out.printf("%s\t%d\t%.4f\t%.4f\t%.4f\t%.4f\t%d\t%s\n",
                        kernel, sd, meanRmse, meanBias, meanRmseAlign, meanOffset,
                        failures, EdgeDiagnostics.summary());
            }
        }
    }

    /**
     * Reference (ground-truth) ESF.
     *
     * IMPORTANT: for the measured error (measured - ideal) to reflect only the
     * effect of noise + detector, the ideal ESF must be sampled with the SAME
     * geometry and the SAME interpolation kernel as the measured ESF in
     * TtfCalc.calculateESF. That was not the case before:
     *   - the measured one walks along the local NORMAL and uses Lanczos-3;
     *   - the ideal one walked only along the Y axis and used 1D linear interp.
     * This method mismatch (Lanczos vs linear; normal vs Y axis) contaminated
     * the RMSE/Bias with "method mismatch" rather than detection error.
     *
     * Here the edge is at the "12 o'clock" position of a circle centered at
     * (centerPx, centerPx), so the normal is exactly vertical. We sample along
     * Y with a 0.5 px step, anchored at the analytic edge edgeY = centerPx -
     * radiusPx, using the SAME Lanczos-3 as TtfCalc
     * (getLanczosInterpolatedPixel). The image passed in must be the NOISE-FREE
     * version (MTF applied) -- that is the true ESF blurred by the kernel.
     *
     * Index convention identical to calculateESF: index 0 = background (outside
     * the insert, smaller Y), last index = object (inside, larger Y).
     */
    private static double[] generateIdealEsfDirect(ImageProcessor ip, double centerPx, double radiusPx, int profileLength, Calibration cal) {
        int half = profileLength / 2;
        double step = 0.5; // same subpixel step as TtfCalc
        double[] esf = new double[profileLength];

        // Central column (12 o'clock). The normal points from outside (smaller
        // Y) toward inside (larger Y), i.e. (nx, ny) = (0, +1).
        double x0 = centerPx;
        double edgeY = centerPx - radiusPx;   // analytic edge

        for (int j = -half; j <= half; j++) {
            double x = x0;
            double y = edgeY + (j * step);  // vertical normal, same direction as the measurement

            // Same interpolation kernel as the measured ESF (Lanczos-3).
            double pixelValue = TtfCalc.getLanczosInterpolatedPixel(ip, x, y);
            esf[j + half] = cal.getCValue(pixelValue);
        }

        return esf;
    }

    /**
     * ESF errors, separating REGISTRATION from SHAPE FIDELITY.
     *
     * Why align? The ideal ESF is anchored at the ANALYTIC edge (step j=0 lands
     * exactly on the true edge), so its 50% point sits at the central index.
     * The measured ESF, on the other hand, is anchored at the DETECTED edge
     * point (TtfCalc uses x0,y0 of the polygon). The detector is subpixel but
     * has a residual shift -- partly random (RMS ~0.06..0.5 px) and partly
     * SYSTEMATIC (the Gaussian pre-blur pushes a circle's edge slightly inward;
     * restoration/cleanWand/3px snap also shift it). Hence the measured ESF's
     * 50% point does NOT land at the central index: it is displaced by the edge
     * positioning error.
     *
     * On a steep segment the ESF changes ~40-50 HU per 0.5 px step, so a 0.2 px
     * shift becomes tens of HU of apparent error with a consistent sign ->
     * inflating RMSE and, especially, Bias, even though the reconstructed
     * INTENSITIES are not wrong.
     *
     * Solution: measure the offset between the two curves via the 50%-contrast
     * crossing, align the measurement (resampling with the SAME Lanczos-3), and
     * only then compute the RMSE. We report:
     *   [0] rmseRaw   - RMSE without alignment (as before)
     *   [1] biasRaw   - Bias without alignment (as before)
     *   [2] rmseAlign - RMSE after removing the lateral shift
     *                   (SHAPE/intensity fidelity, ~independent of the edge
     *                    position)
     *   [3] offsetPx  - registration shift in pixels (positive = detected edge
     *                   displaced toward +index relative to the analytic one);
     *                   measures the detector's POSITIONING BIAS.
     */
    private static double[] esfError(double[] measured, double[] ideal) {
        if (measured.length != ideal.length)
            return new double[]{Double.NaN, Double.NaN, Double.NaN, Double.NaN};

        int n = measured.length;
        double step = 0.5; // px per sample, same as TtfCalc

        // --- raw metrics (not aligned) ---
        double sumSq = 0.0, sumBias = 0.0;
        for (int i = 0; i < n; i++) {
            double e = measured[i] - ideal[i];
            sumBias += e;
            sumSq += e * e;
        }
        double rmseRaw = Math.sqrt(sumSq / n);
        double biasRaw = sumBias / n;

        // --- registration offset via the 50%-contrast crossing ---
        double idxMeas = halfRiseIndex(measured);
        double idxIdeal = halfRiseIndex(ideal);
        double rmseAlign = Double.NaN, offsetPx = Double.NaN;
        if (!Double.isNaN(idxMeas) && !Double.isNaN(idxIdeal)) {
            double shift = idxMeas - idxIdeal;          // in samples (steps)
            offsetPx = shift * step;                    // in pixels

            // Resample the measurement shifted by -shift to match the ideal,
            // using Lanczos-3 (same ESF kernel). Compare only the valid interior
            // region (margin = Lanczos radius) to avoid the borders.
            int a = 3;
            double sq = 0; int cnt = 0;
            for (int i = 0; i < n; i++) {
                double src = i + shift;                 // position in the measurement matching index i of the ideal
                if (src < a || src > n - 1 - a) continue; // outside the kernel's safe support
                double m = lanczosResample(measured, src, a);
                double e = m - ideal[i];
                sq += e * e; cnt++;
            }
            if (cnt > 0) rmseAlign = Math.sqrt(sq / cnt);
        }

        return new double[]{rmseRaw, biasRaw, rmseAlign, offsetPx};
    }

    /**
     * Fractional index where the ESF crosses 50% between its background level
     * (mean of the first 3 points) and object level (mean of the last 3).
     * Linear interpolation between the two samples bracketing the crossing.
     * Robust for the monotonic SOFT ESF and also for the non-monotonic Lung
     * one, because it uses the first crossing of the mid level on the rise.
     */
    private static double halfRiseIndex(double[] esf) {
        int n = esf.length;
        if (n < 7) return Double.NaN;
        double bg = (esf[0] + esf[1] + esf[2]) / 3.0;
        double obj = (esf[n - 1] + esf[n - 2] + esf[n - 3]) / 3.0;
        double mid = 0.5 * (bg + obj);
        boolean rising = obj >= bg;
        for (int i = 0; i < n - 1; i++) {
            double a = esf[i], b = esf[i + 1];
            boolean crosses = rising ? (a <= mid && b >= mid) : (a >= mid && b <= mid);
            if (crosses) {
                double denom = (b - a);
                double frac = denom != 0 ? (mid - a) / denom : 0.0;
                return i + frac;
            }
        }
        return Double.NaN;
    }

    /** Lanczos-3 resampling of a 1D signal at a fractional position. */
    private static double lanczosResample(double[] s, double pos, int a) {
        int n = s.length;
        int base = (int) Math.floor(pos);
        double sum = 0, w = 0;
        for (int k = base - a + 1; k <= base + a; k++) {
            int idx = Math.max(0, Math.min(n - 1, k));
            double d = pos - k;
            double wt = lanczos1d(d, a);
            sum += s[idx] * wt;
            w += wt;
        }
        return w != 0 ? sum / w : 0;
    }

    private static double lanczos1d(double x, int a) {
        if (x == 0.0) return 1.0;
        if (Math.abs(x) >= a) return 0.0;
        double piX = Math.PI * x;
        return (a * Math.sin(piX) * Math.sin(piX / a)) / (piX * piX);
    }

    private static double calculateMean(List<Double> values) {
        if (values == null || values.isEmpty()) return Double.NaN;
        double sum = 0;
        for (double v : values) sum += v;
        return sum / values.size();
    }
}