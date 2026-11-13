package br.rededor.fisicamedica;

import java.awt.Rectangle;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.OvalRoi;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;

/**
 * Classe para cálculo automatizado de Detectabilidade (d') no Phantom ACR.
 * Refatorada para alta robustez na detecção de fatias e beads.
 */
public class AcrAutoDetectability {

	public static class AutoDetectResult {
        public double dPrime;
        public double auc;
        
        public double globalNoise;
        public double f50;
        public double f10;
        
        public double[] ttfFreq;
        public double[] ttfData;
        public double[] npsFreq;
        public double[] npsData;
        public int mod1Start, mod1End;
        public int mod3Start, mod3End;
        public int validNpsSlices;
    }

    public static AutoDetectResult runAutoACR(
            String targetMaterial, int numRois, int roiSizePx, double distributionRadMM,
            double taskRadius, double taskContrast, double viewDistance, double displaySize, double fov) {
    	
        SeriesData sd = ImageOpener.getActiveSeriesData();
        if (sd == null || sd.getFiles().isEmpty()) {
            IJ.error("Nenhuma série ativa encontrada.");
            return null;
        }

        ImageStack stack = new ImageStack();
        Calibration cal = null;
        for (File f : sd.getFiles()) {
            ImagePlus imp = new ImagePlus(f.getAbsolutePath());
            if (imp.getWidth() == 0) continue;
            if (cal == null) cal = imp.getCalibration();
            stack.addSlice(imp.getProcessor());
        }

        // Garante que o pixel size existe (fallback para 0.5 se vier zerado)
        double pSizeX = (cal != null && cal.pixelWidth > 0) ? cal.pixelWidth : 0.5;
        double pSizeY = (cal != null && cal.pixelHeight > 0) ? cal.pixelHeight : 0.5;

        AutoDetectResult result = new AutoDetectResult();

        // PASSO 1: Encontrar os limites do phantom via Área de Massa
        int[] zLimits = findPhantomLimitsByArea(stack, cal, pSizeX, pSizeY);
        int zStart = zLimits[0];
        int zEnd = zLimits[1];
        
        if (zStart == -1 || zEnd == -1) {
            IJ.error("Não foi possível localizar o fantoma (área cilíndrica insuficiente).");
            return null;
        }

        // PASSO 2: Caçar o Módulo 1 pela assinatura de densidade (Ar + Osso)
        List<Integer> mod1Slices = findModule1Slices(stack, zStart, zEnd, cal, pSizeX, pSizeY);
        if (mod1Slices.isEmpty()) {
            IJ.error("Não foi possível encontrar o Módulo 1 (Insertos de Ar e Osso ausentes).");
            return null;
        }

        Collections.sort(mod1Slices);
        int mod1CenterSlice = mod1Slices.get(mod1Slices.size() / 2);
        
        // PASSO 3: Mapear os Módulos geometricamente
        int totalPhantomSlices = zEnd - zStart + 1;
        double moduleLength = totalPhantomSlices / 4.0;
        double safetyOffset = moduleLength * 0.25; // Margem para fugir das bordas do módulo

        boolean mod1AtStart = mod1CenterSlice < (zStart + totalPhantomSlices / 2);

        int mod3CenterSlice = mod1AtStart ? 
            (int) Math.round(mod1CenterSlice + (2 * moduleLength)) : 
            (int) Math.round(mod1CenterSlice - (2 * moduleLength));

        result.mod1Start = (int) Math.round(mod1CenterSlice - moduleLength/2 + safetyOffset);
        result.mod1End   = (int) Math.round(mod1CenterSlice + moduleLength/2 - safetyOffset);
        
        result.mod3Start = (int) Math.round(mod3CenterSlice - moduleLength/2 + safetyOffset);
        result.mod3End   = (int) Math.round(mod3CenterSlice + moduleLength/2 - safetyOffset);

        IJ.log("Limites do Phantom: " + zStart + " a " + zEnd);
        IJ.log("Centro do Módulo 1 detectado na fatia: " + mod1CenterSlice);
        IJ.log("Módulo 1 (TTF) fatias válidas: " + result.mod1Start + " a " + result.mod1End);
        IJ.log("Módulo 3 (NPS) fatias estimadas: " + result.mod3Start + " a " + result.mod3End);

        // PASSO 4: Filtrar Módulo 3 (Remover BBs)
        List<Integer> validMod3Slices = filterUniformitySlices(stack, result.mod3Start, result.mod3End, cal, pSizeX, pSizeY);
        result.validNpsSlices = validMod3Slices.size();
        
        if (validMod3Slices.isEmpty()) {
            IJ.error("Todas as fatias do Módulo 3 falharam no teste de uniformidade (Beads ou bordas detectadas).");
            return null;
        }
        IJ.log("Fatias limpas para NPS (sem beads): " + result.validNpsSlices);

        // PASSO 5: TTF
        calculateTTF(stack, cal, result.mod1Start, result.mod1End, targetMaterial, result);
        if (result.ttfData == null) {
            IJ.error("Falha ao calcular TTF no Módulo 1.");
            return null;
        }

        // PASSO 6: NPS
        calculateNPS(stack, cal, validMod3Slices, numRois, roiSizePx, distributionRadMM, result);
        if (result.npsData == null) {
            IJ.error("Falha ao calcular NPS no Módulo 3.");
            return null;
        }

        // PASSO 7: d'
        DetectabilityIndexCalculator dCalc = new DetectabilityIndexCalculator(
                result.ttfData, result.ttfFreq,
                result.npsData, result.npsFreq,
                taskRadius, taskContrast, viewDistance, displaySize, fov);
        
        result.dPrime = dCalc.compute();
        result.auc = 0.5 + 0.5 * org.apache.commons.math3.special.Erf.erf(result.dPrime / 2.0);

        return result;
    }

    /**
     * Encontra os limites do phantom calculando a área física.
     * O fantoma ACR tem área de ~31.400 mm2.
     */
    private static int[] findPhantomLimitsByArea(ImageStack stack, Calibration cal, double px, double py) {
        int start = -1;
        int end = -1;
        double pixelArea = px * py;

        for (int i = 1; i <= stack.getSize(); i++) {
            ImageProcessor ip = stack.getProcessor(i);
            
            int count = 0;
            int numPixels = ip.getPixelCount();
            
            for (int p = 0; p < numPixels; p++) {
                // Pega o valor bruto (float ou short) e aplica a calibração DICOM (Slope/Intercept)
                double huValue = (cal != null) ? cal.getCValue(ip.getf(p)) : ip.getf(p);
                
                if (huValue > -500.0) {
                    count++;
                }
            }
            
            double areaMM2 = count * pixelArea;
            
            // Tolerância: entre 15.000 e 45.000 mm2
            if (areaMM2 > 15000 && areaMM2 < 45000) {
                if (start == -1) start = i;
                end = i;
            }
        }
        return new int[]{start, end};
    }

    /**
     * Caça o Módulo 1 procurando a fatia que contém picos de Ar (< -700) e Osso (> 700) simultâneos.
     */
    private static List<Integer> findModule1Slices(ImageStack stack, int zStart, int zEnd, Calibration cal, double px, double py) {
        List<Integer> mod1Slices = new ArrayList<>();
        int w = stack.getWidth();
        int h = stack.getHeight();
        
        // ROI central de 140mm de diâmetro para englobar os insertos e ignorar o ar de fora
        double radiusPxX = 70.0 / px;
        double radiusPxY = 70.0 / py;
        Roi centerRoi = new OvalRoi((w/2.0) - radiusPxX, (h/2.0) - radiusPxY, radiusPxX*2, radiusPxY*2);

        IJ.log("\n--- CAÇANDO O MÓDULO 1 ---");
        IJ.log("Analisando fatias " + zStart + " a " + zEnd + " com ROI de raio " + String.format("%.0f", radiusPxX) + " px");

        for (int i = zStart; i <= zEnd; i++) {
            ImageProcessor ip = stack.getProcessor(i);
            ip.setRoi(centerRoi);
            ImageStatistics stats = ImageStatistics.getStatistics(ip);
            
            // CONVERSÃO VITAL: Transforma o min e max bruto do ImageStatistics para a escala HU real
            double minHU = (cal != null) ? cal.getCValue(stats.min) : stats.min;
            double maxHU = (cal != null) ? cal.getCValue(stats.max) : stats.max;
            
            // Se as fatias passarem perto da zona de interesse, vamos logar para depurar
            if (minHU < -500 || maxHU > 500) {
                IJ.log("Fatia " + i + " -> Min: " + String.format("%6.0f", minHU) + " HU | Max: " + String.format("%6.0f", maxHU) + " HU");
            }

            // Assinatura do Módulo 1: Presença de Ar (< -700 HU) e Osso (> 700 HU)
            if (minHU < -700 && maxHU > 700) {
                mod1Slices.add(i);
                IJ.log("  >> Assinatura CONFIRMADA na fatia " + i);
            }
        }
        IJ.log("--------------------------\n");
        return mod1Slices;
    }

    /**
     * Filtra fatias com Beads no Módulo 3.
     * Os beads ficam a 50mm do centro. O ROI precisa ter pelo menos 140mm de diâmetro para enxergá-los.
     * Usamos > 120 HU para o limite do metal/teflon para não esbarrar em ruído natural de dose baixa.
     */
    private static List<Integer> filterUniformitySlices(ImageStack stack, int start, int end, Calibration cal, double px, double py) {
        List<Integer> validSlices = new ArrayList<>();
        int w = stack.getWidth();
        int h = stack.getHeight();
        
        // ROI de 160 mm de diâmetro (engloba os beads, mas foge do anel de plástico externo)
        double radiusPxX = 80.0 / px;
        double radiusPxY = 80.0 / py;
        Roi waterRoi = new OvalRoi((w/2.0) - radiusPxX, (h/2.0) - radiusPxY, radiusPxX*2, radiusPxY*2);

        IJ.log("\n--- FILTRANDO MÓDULO 3 (BEADS) ---");
        IJ.log("Avaliando fatias " + start + " a " + end);

        for (int i = start; i <= end; i++) {
            ImageProcessor ip = stack.getProcessor(i);
            ip.setRoi(waterRoi);
            ImageStatistics stats = ImageStatistics.getStatistics(ip);
            
            // CORREÇÃO CRÍTICA: Converte o valor bruto para Hounsfield Units!
            double maxHU = (cal != null) ? cal.getCValue(stats.max) : stats.max;
            
            // O seu print salva-vidas
            IJ.log("Fatia " + i + " -> Max: " + String.format("%6.0f", maxHU) + " HU");

            // Se for água limpa (ou com ruído normal), o maxHU não passa muito de 50.
            // Se bater em um Bead de metal, pula pra mais de 500 HU rapidamente.
            if (maxHU <= 90) {
                validSlices.add(i);
                IJ.log("  >> Fatia LIMPA (Aprovada)");
            } else {
                IJ.log("  >> Fatia DESCARTADA (Bead ou artefato detectado)");
            }
        }
        IJ.log("----------------------------------\n");
        return validSlices;
    }

    //-------------------------------------------------------------------------
    // Funções de Cálculo (Idênticas ao pipeline original)
    //-------------------------------------------------------------------------

    private static void calculateTTF(ImageStack stack, Calibration cal, int start, int end, String targetMaterial, AutoDetectResult result) {
        ImageStack mod1Stack = new ImageStack();
        for (int i = start; i <= end; i++) {
            mod1Stack.addSlice(stack.getProcessor(i));
        }

        try {
            Segmenter.DetectorResult detResult = Segmenter.edgeFinder(mod1Stack, cal, null, true, true);
            if (detResult == null || detResult.rois.isEmpty()) return;

            TtfCalc.mtfResult mtfRes = TtfCalc.analisarStackComInserts(mod1Stack, cal, detResult, 128);
            
            IJ.log("\n--- IDENTIFICAÇÃO DOS MATERIAIS (TTF) ---");
            int selectedIndex = -1;
            
            // Avalia o HU interno (I1) de todos os insertos detectados
            for (Integer key : mtfRes.I1.keySet()) {
                double hu = mtfRes.I1.get(key);
                String mat = "Unknown";
                
                if (hu < -700) mat = "Air";
                else if (hu > 500) mat = "Bone";
                else if (hu > 50 && hu < 300) mat = "Acrylic";
                else if (hu < -30 && hu > -250) mat = "Polyethylene";
                
                IJ.log("Inserto " + key + " -> HU médio: " + String.format("%6.0f", hu) + " (" + mat + ")");
                
                if (mat.equals(targetMaterial)) {
                    selectedIndex = key;
                }
            }
            IJ.log("-----------------------------------------");

            if (selectedIndex != -1 && mtfRes.ttfs.containsKey(selectedIndex)) {
                result.ttfData = mtfRes.ttfs.get(selectedIndex);
                result.ttfFreq = mtfRes.freqs.get(selectedIndex);
                IJ.log(">> Módulo TTF associado ao inserto: " + targetMaterial + " (Índice " + selectedIndex + ")\n");
            } else {
                IJ.log(">> AVISO: Material '" + targetMaterial + "' não encontrado! Usando o primeiro detectado como fallback.\n");
                int firstKey = mtfRes.ttfs.keySet().iterator().next();
                result.ttfData = mtfRes.ttfs.get(firstKey);
                result.ttfFreq = mtfRes.freqs.get(firstKey);
            }
            if (result.ttfData != null) {
                result.f50 = findFrequencyAtLevel(result.ttfFreq, result.ttfData, 0.5);
                result.f10 = findFrequencyAtLevel(result.ttfFreq, result.ttfData, 0.1);
            }
        } catch (Exception e) {
            IJ.handleException(e);
        }
    }

    private static void calculateNPS(ImageStack stack, Calibration cal, List<Integer> validSlices, int numRois, int roiSizePx, double distRadiusMM, AutoDetectResult result) {
        double[][] sumNPS2D = new double[roiSizePx][roiSizePx];
        int validRecordsTotal = 0;
        double sumVariance = 0.0;
        
        for (int sliceIndex : validSlices) {
            ImageProcessor ip = stack.getProcessor(sliceIndex);
            
            PolygonRoi phantomOutline = Segmenter.segmenter(ip, cal, -200, 5000, 0.2, false, 0, 20);
            
            List<Roi> records = Segmenter.createCircularRecordRois(
                    ip, cal, phantomOutline, numRois, roiSizePx, distRadiusMM, false, sliceIndex);

            for (Roi r : records) {
                ip.setRoi(r);
                ImageProcessor record = ip.crop();
                
                ImageStatistics stats = ImageStatistics.getStatistics(record);
                int N = record.getWidth() * record.getHeight();
                sumVariance += Math.pow(stats.stdDev, 2) * (double)(N - 1) / N;
                
                float[][] roiData = record.getFloatArray(); 
                double[][] detrendedRoiData = Detrending.detrendWith2ndOrderPolynomial(roiData);
                
                float[][] detrendedFloatData = new float[roiSizePx][roiSizePx]; 
                for (int x = 0; x < roiSizePx; x++) {
                    for (int y = 0; y < roiSizePx; y++) {
                        detrendedFloatData[x][y] = (float) detrendedRoiData[x][y];
                    }
                }
                record = new ij.process.FloatProcessor(detrendedFloatData);
                
                double[][] powerSpectrum = NPScalc.calculate2DNPS(record, cal, roiSizePx);
                
                for (int y = 0; y < roiSizePx; y++) {
                    for (int x = 0; x < roiSizePx; x++) {
                        sumNPS2D[y][x] += powerSpectrum[y][x];
                    }
                }
                validRecordsTotal++;
            }
        }

        if (validRecordsTotal == 0) return;
        result.globalNoise = Math.sqrt(sumVariance / validRecordsTotal);

        for (int y = 0; y < roiSizePx; y++) {
            for (int x = 0; x < roiSizePx; x++) {
                sumNPS2D[y][x] /= validRecordsTotal;
            }
        }

        double[] radialFreq = new double[roiSizePx / 2];
        double[] radialNPS = NPScalc.radialProfile(sumNPS2D, cal, radialFreq);

        try {
            org.apache.commons.math3.fitting.WeightedObservedPoints obs = new org.apache.commons.math3.fitting.WeightedObservedPoints();
            for(int i=1; i<=5; i++) {
                if(i < radialFreq.length) obs.add(radialFreq[i], radialNPS[i]);
            }
            double[] coeff = org.apache.commons.math3.fitting.PolynomialCurveFitter.create(2).fit(obs.toList());
            radialNPS[0] = Math.max(0, coeff[0]);
        } catch (Exception e) {}

        result.npsData = radialNPS;
        result.npsFreq = radialFreq;
    }
    /**
     * Encontra a frequência exata onde a curva (TTF) cruza um nível alvo (ex: 0.5) caindo.
     * Utiliza interpolação linear entre os pontos discretos da transformada.
     */
    private static double findFrequencyAtLevel(double[] freq, double[] ttf, double targetLevel) {
        if (freq == null || ttf == null || freq.length == 0 || ttf.length == 0) return 0.0;
        
        for (int i = 0; i < ttf.length - 1; i++) {
            // Procura o momento em que a curva corta o 'targetLevel' de cima para baixo
            if (ttf[i] >= targetLevel && ttf[i+1] <= targetLevel) {
                double f0 = freq[i];
                double f1 = freq[i+1];
                double t0 = ttf[i];
                double t1 = ttf[i+1];
                
                if (t0 == t1) return f0; // Evita divisão por zero
                
                // Equação da reta para interpolação: x = x0 + (y_alvo - y0)*(x1 - x0)/(y1 - y0)
                return f0 + (targetLevel - t0) * (f1 - f0) / (t1 - t0);
            }
        }
        return 0.0; // Retorna 0 se a curva nunca atingir o nível (ruído anômalo, por ex.)
    }
}