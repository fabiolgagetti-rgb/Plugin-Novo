package br.rededor.fisicamedica;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.process.FloatPolygon;
import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import br.rededor.fisicamedica.Segmenter.DetectorResult;

public class EdgeValidation {

    public static void main(String[] args) {
        
        System.out.println("Kernel\tNoise(SD)\tCenterShift(mm)\tRadialBias(mm)\tRadialRMSE(mm)\tFailures");

        int nHomogeneousSlices = 0;
        int nInsertSlices = 1;
        int matrix = 512;
        double fov = 250.0;
        double bgHU = -1000.0;
        double objectMeanHU = 0.0; 
        double diameterMM = 200.0; 
        double trendAmpHU = 0.0;
        double[] insertHU = {100}; // Insert contrast
        double insertRadiusMM = 12.5; // True radius (ground truth)
        double insertDistanceMM = 0.0; // Sits exactly at the center
        
        double pixelSize = fov / matrix; 
        
        Calibration cal = new Calibration();
        cal.pixelWidth = pixelSize;
        cal.pixelHeight = pixelSize;
        cal.setUnit("mm");
        
        int roiSize = (int) (insertRadiusMM * 3 / pixelSize);
        double gtCenterPx = matrix / 2.0; // True center (ground truth) in pixels
        
        int roiX = (int) gtCenterPx - (roiSize / 2);
        int roiY = (int) gtCenterPx - (roiSize / 2);
        Roi userRoi = new Roi(roiX, roiY, roiSize, roiSize);
        
        String[] kernels = {"SOFT", "Lung"}; 
        int[] noiseLevelsSD = {0, 1, 10, 20, 30, 40};
        int numMeasurements = 30;

        for (String kernel : kernels) {
            for (int sd : noiseLevelsSD) {
                double variance = sd * sd; 
                
                List<Double> centerShiftList = new ArrayList<>();
                List<Double> radialBiasList = new ArrayList<>();
                List<Double> radialRmseList = new ArrayList<>();
                int failures = 0;
                
                for (int i = 0; i < numMeasurements; i++) {
                    try {
                        PhantomImageCreator creator = new PhantomImageCreator();
                        ImagePlus imp = creator.criarImagemCTPhantom(
                            nHomogeneousSlices, nInsertSlices, matrix, fov, bgHU, objectMeanHU, 
                            diameterMM, trendAmpHU, insertHU, insertRadiusMM, insertDistanceMM, 
                            kernel, variance
                        );
                        
                        // Pass false, true (the last flag enables insert detection)
                        DetectorResult dR = Segmenter.edgeFinder(imp.getStack(), cal, userRoi, false, true);
                        
                        if (dR == null || dR.rois.isEmpty() || dR.rois.get(0).isEmpty()) {
                            failures++;
                            continue;
                        }
                        
                        // Get the detected center and the subpixel edge polygon
                        Point detectedCenter = dR.Pos.get(0);
                        Roi borderRoi = dR.rois.get(0).get(0);
                        FloatPolygon boundary = borderRoi.getFloatPolygon();
                        
                        if (boundary == null || boundary.npoints == 0) {
                            failures++;
                            continue;
                        }

                        // 1. Compute the center error in mm
                        double dx = (detectedCenter.x - gtCenterPx) * pixelSize;
                        double dy = (detectedCenter.y - gtCenterPx) * pixelSize;
                        double centerShiftMM = Math.sqrt(dx * dx + dy * dy);
                        
                        // 2 & 3. Compute the bias and RMSE of the detected edge in mm
                        double[] radialMetrics = radialMetrics(boundary, gtCenterPx, gtCenterPx, insertRadiusMM, pixelSize);
                        
                        centerShiftList.add(centerShiftMM);
                        radialBiasList.add(radialMetrics[0]);
                        radialRmseList.add(radialMetrics[1]);
                        
                    } catch (Exception e) {
                        failures++;
                    }
                }
                
                // Aggregate the results (mean of each metric)
                double meanCenterShift = calculateMean(centerShiftList);
                double meanRadialBias = calculateMean(radialBiasList);
                double meanRadialRmse = calculateMean(radialRmseList);
                
                // Formatted print, ready to paste into Excel/Origin
                System.out.printf("%s\t%d\t%.4f\t%.4f\t%.4f\t%d\n", 
                                  kernel, sd, meanCenterShift, meanRadialBias, meanRadialRmse, failures);
            }
        }
    }

    /**
     * Computes the bias and root-mean-square error (RMSE) of the detected edge
     * compared with the ideal circle.
     * Returns: [0] = Bias (mm), [1] = RMSE (mm)
     */
    private static double[] radialMetrics(FloatPolygon boundary, double gtCenterX, double gtCenterY, double gtRadiusMM, double pixelSize) {
        double sumError = 0.0;
        double sumSqError = 0.0;
        int n = boundary.npoints;
        
        for (int k = 0; k < n; k++) {
            // Get the subpixel coordinate of the edge point
            float px = boundary.xpoints[k];
            float py = boundary.ypoints[k];
            
            // Distance from the point to the true center (in pixels)
            double distPx = Math.sqrt(Math.pow(px - gtCenterX, 2) + Math.pow(py - gtCenterY, 2));
            
            // Convert to mm and find the error relative to the perfect radius
            double distMM = distPx * pixelSize;
            double errorMM = distMM - gtRadiusMM; 
            
            sumError += errorMM;
            sumSqError += (errorMM * errorMM);
        }
        
        double bias = sumError / n; // If positive, the edge was detected larger than reality
        double rmse = Math.sqrt(sumSqError / n); // Overall edge spread (roughness)
        
        return new double[]{bias, rmse};
    }

    private static double calculateMean(List<Double> values) {
        if (values == null || values.isEmpty()) return Double.NaN;
        double sum = 0;
        for (double v : values) sum += v;
        return sum / values.size();
    }
}