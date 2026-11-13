package br.rededor.fisicamedica;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

/**
 * Utilitario de calibracao do CTVarianceMapEstimator.
 *
 * Fundamento: a variancia final propagada pela Eq. (5) escala com o quadrado do
 * fator de normalizacao C:  sigma^2_final(x,y) = C^2 * sigma^2_base(x,y),
 * onde sigma^2_base e o mapa produzido com C = 1. Logo, dado o alvo de variancia
 * (medido pelo NPScalc num phantom homogeneo REAL, mesma tecnica) e a variancia
 * que o estimador produz numa ROI equivalente com C = 1, o C exato sai em UMA conta:
 *
 *     C_calibrado = sqrt( varAlvo / varBase_naROI )
 *
 * Nao ha necessidade de iterar, pois a relacao e linear em C^2.
 */
public final class VarianceMapCalibrator {

    private VarianceMapCalibrator() { }

    public static final class CalibrationResult {
        public final double calibratedC;      // C a ser usado (ja aplicado ao estimador)
        public final double targetVariance;   // variancia-alvo (NPScalc, phantom real)
        public final double baseVariance;     // variancia media na ROI com C = 1
        public final double previousC;         // C que estava no estimador antes da calibracao

        CalibrationResult(double calibratedC, double targetVariance,
                          double baseVariance, double previousC) {
            this.calibratedC = calibratedC;
            this.targetVariance = targetVariance;
            this.baseVariance = baseVariance;
            this.previousC = previousC;
        }

        @Override
        public String toString() {
            return String.format(
                "CalibrationResult{C=%.6g, targetVar=%.6g, baseVar=%.6g, prevC=%.6g}",
                calibratedC, targetVariance, baseVariance, previousC);
        }
    }

    /**
     * Calcula o C calibrado a partir de um phantom HOMOGENEO simulado e da variancia-alvo.
     * Ao final, o estimador fica com setNormalizationConstant(calibratedC) aplicado.
     *
     * @param estimator          estimador ja configurado (geometria, espectro, N0...).
     * @param homogeneousPhantom phantom homogeneo simulado em HU, mesmas geometria/pixel spacing.
     * @param targetVariance     variancia-alvo do NPScalc no phantom real (mesmas unidades^2).
     * @param roiFraction        fracao (0..1] do lado da imagem usada como ROI central de medida.
     */
    public static CalibrationResult calibrate(CTVarianceMapEstimator estimator,
                                              ImagePlus homogeneousPhantom,
                                              double targetVariance,
                                              double roiFraction) {
        if (estimator == null || homogeneousPhantom == null) {
            throw new IllegalArgumentException("estimator e homogeneousPhantom nao podem ser nulos.");
        }
        if (targetVariance <= 0) {
            throw new IllegalArgumentException("targetVariance deve ser positiva.");
        }
        if (roiFraction <= 0 || roiFraction > 1.0) {
            throw new IllegalArgumentException("roiFraction deve estar em (0, 1].");
        }

        // Preserva o C atual (agora possivel gracas ao getter).
        final double previousC = estimator.getNormalizationConstant();

        // Mede o mapa "base" com C = 1.
        estimator.setNormalizationConstant(1.0);
        ImagePlus baseMap = estimator.computeVarianceMap(homogeneousPhantom);

        double baseVariance = meanCentralRoi(baseMap, roiFraction);
        if (baseVariance <= 0) {
            // Restaura antes de falhar, para nao deixar o estimador em estado inesperado.
            estimator.setNormalizationConstant(previousC);
            throw new IllegalStateException(
                "Variancia base nao-positiva na ROI (baseVariance=" + baseVariance
                + "). Verifique geometria/N0/phantom.");
        }

        double calibratedC = Math.sqrt(targetVariance / baseVariance);

        // Deixa o estimador pronto com o C calibrado.
        estimator.setNormalizationConstant(calibratedC);

        return new CalibrationResult(calibratedC, targetVariance, baseVariance, previousC);
    }

    /** Sobrecarga com ROI central padrao de 25% do lado da imagem. */
    public static CalibrationResult calibrate(CTVarianceMapEstimator estimator,
                                              ImagePlus homogeneousPhantom,
                                              double targetVariance) {
        return calibrate(estimator, homogeneousPhantom, targetVariance, 0.25);
    }

    /** Variante que aceita um DESVIO-PADRAO alvo (sigma) em vez de variancia. */
    public static CalibrationResult calibrateFromTargetStdDev(CTVarianceMapEstimator estimator,
                                                              ImagePlus homogeneousPhantom,
                                                              double targetStdDev,
                                                              double roiFraction) {
        if (targetStdDev <= 0) {
            throw new IllegalArgumentException("targetStdDev deve ser positiva.");
        }
        return calibrate(estimator, homogeneousPhantom, targetStdDev * targetStdDev, roiFraction);
    }

    /**
     * Media dos valores do mapa de variancia numa ROI central retangular,
     * promediada sobre todas as fatias do stack.
     */
    private static double meanCentralRoi(ImagePlus varianceMap, double roiFraction) {
        ImageStack st = varianceMap.getStack();
        int w = st.getWidth();
        int h = st.getHeight();
        int nSlices = st.getSize();

        int roiW = Math.max(1, (int) Math.round(w * roiFraction));
        int roiH = Math.max(1, (int) Math.round(h * roiFraction));
        int x0 = (w - roiW) / 2;
        int y0 = (h - roiH) / 2;

        double sum = 0.0;
        long count = 0;

        for (int s = 1; s <= nSlices; s++) {
            ImageProcessor ip = st.getProcessor(s);
            FloatProcessor fp = ip.convertToFloatProcessor();
            for (int y = y0; y < y0 + roiH; y++) {
                for (int x = x0; x < x0 + roiW; x++) {
                    sum += fp.getf(x, y);
                    count++;
                }
            }
        }
        return (count > 0) ? sum / count : 0.0;
    }
}
