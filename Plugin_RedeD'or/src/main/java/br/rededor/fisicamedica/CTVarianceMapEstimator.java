package br.rededor.fisicamedica;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * Estimates the spatially varying noise VARIANCE MAP of a reconstructed CT image,
 * implementing Section II.A ("Variance Map Estimation") of:
 * S. E. Divel and N. J. Pelc, "Accurate image domain noise insertion in CT images,"
 * IEEE Transactions on Medical Imaging, 2020. DOI: 10.1109/TMI.2019.2961837
 *
 * IMPORTANTE (contexto de uso neste pipeline):
 *  - Esta classe entrega um MAPA DE AMPLITUDE (variancia sigma^2 por pixel).
 *  - A TEXTURA (correlacao/frequencia) do ruido NAO vem daqui; vem da NPS medida,
 *    aplicada separadamente no injetor de ruido. Portanto o kernel Ram-Lak padrao
 *    e suficiente para o formato espacial da magnitude; nao precisa reproduzir a
 *    textura de frequencia.
 *
 * PIPELINE:
 *  - Multi-threaded ray-marching e backprojection pixel-driven.
 *  - Geometria exata para leque equiangular.
 *  - Offset fracionario de detector (ex.: 1/4 detector da GE).
 *  - Perfil de filtro Bowtie via array de fotons por canal.
 */
public class CTVarianceMapEstimator {

    private boolean backprojectionUsesL4 = true;

    // ---- Required scan geometry --------------------------------------------------
    private final double sourceToIsocenterDistanceMM;
    private final double sourceToDetectorDistanceMM;
    private final int numViews;
    private final double[] fanAnglesRad;
    private final int numDetectors;
    private final double pixelSpacingMM;

    // ---- Reconstruction kernel (real space) --------------------------------------
    private double[] kernelH;

    // ---- X-ray spectrum / attenuation model --------------------------------------
    private double[] spectrumEnergyKeV;
    private double[] spectrumWeight;
    private double[] muWaterPerCm;
    private double[] incidentPhotonsPerRay; // array para modelar o Bowtie

    // ---- Normalization -----------------------------------------------------------
    private double normalizationConstant;

    // ---- Forward-projector ray-marching sampling ---------------------------------
    private double forwardProjectionStepMM;

    /** Construtor principal (array customizado de angulos de leque). */
    public CTVarianceMapEstimator(double sourceToIsocenterDistanceMM,
                                  double sourceToDetectorDistanceMM,
                                  int numViews,
                                  double[] fanAnglesRad,
                                  double pixelSpacingMM) {
        if (fanAnglesRad == null || fanAnglesRad.length < 3) {
            throw new IllegalArgumentException("fanAnglesRad needs at least 3 detector channels");
        }
        if (numViews <= 0 || pixelSpacingMM <= 0 || sourceToIsocenterDistanceMM <= 0) {
            throw new IllegalArgumentException("numViews, pixelSpacingMM and sourceToIsocenterDistanceMM must be positive");
        }

        this.sourceToIsocenterDistanceMM = sourceToIsocenterDistanceMM;
        this.sourceToDetectorDistanceMM = sourceToDetectorDistanceMM;
        this.numViews = numViews;
        this.fanAnglesRad = fanAnglesRad.clone();
        this.numDetectors = fanAnglesRad.length;
        this.pixelSpacingMM = pixelSpacingMM;

        double meanSpacing = Math.abs(fanAnglesRad[numDetectors - 1] - fanAnglesRad[0]) / (numDetectors - 1);
        int defaultKernelLength = Math.min(101, numDetectors);
        if (defaultKernelLength % 2 == 0) defaultKernelLength--;
        this.kernelH = ramLakKernelEquiangular(defaultKernelLength, meanSpacing);

        this.spectrumEnergyKeV = new double[]{70.0};
        this.spectrumWeight = new double[]{1.0};
        this.muWaterPerCm = new double[]{0.19};

        this.incidentPhotonsPerRay = new double[numDetectors];
        Arrays.fill(this.incidentPhotonsPerRay, 1.0e5);

        this.normalizationConstant = 1.0;
        this.forwardProjectionStepMM = 0.5 * pixelSpacingMM;
    }

    /** Construtor de conveniencia com geracao automatica de angulos e offset. */
    public CTVarianceMapEstimator(double sourceToIsocenterDistanceMM,
                                  double sourceToDetectorDistanceMM,
                                  int numViews,
                                  int numDetectors,
                                  double fanAngleSpacingRad,
                                  double pixelSpacingMM,
                                  double quarterDetectorOffset) {
        this(sourceToIsocenterDistanceMM, sourceToDetectorDistanceMM, numViews,
             equiangularFanAngles(numDetectors, fanAngleSpacingRad, quarterDetectorOffset), pixelSpacingMM);
    }

    // =====================================================================
    // Parametros fisicos e calibracao (com validacoes restauradas)
    // =====================================================================

    public void setReconstructionKernel(double[] kernelH) {
        if (kernelH == null || kernelH.length % 2 == 0) {
            throw new IllegalArgumentException("kernelH must be non-null with odd length (symmetric, centered at 0)");
        }
        this.kernelH = kernelH.clone();
    }

    public void setSpectrum(double[] energiesKeV, double[] weights, double[] muWaterPerCm) {
        if (energiesKeV == null || weights == null || muWaterPerCm == null
                || energiesKeV.length != weights.length || energiesKeV.length != muWaterPerCm.length
                || energiesKeV.length == 0) {
            throw new IllegalArgumentException("energiesKeV, weights and muWaterPerCm must be non-null, non-empty and of equal length");
        }
        this.spectrumEnergyKeV = energiesKeV.clone();
        this.spectrumWeight = weights.clone();
        this.muWaterPerCm = muWaterPerCm.clone();
    }

    public void setMonoenergetic(double energyKeV, double muWaterPerCm) {
        setSpectrum(new double[]{energyKeV}, new double[]{1.0}, new double[]{muWaterPerCm});
    }

    /** Perfil Bowtie: array com o mesmo tamanho de numDetectors. */
    public void setIncidentPhotonsPerRay(double[] incidentPhotonsProfile) {
        if (incidentPhotonsProfile == null || incidentPhotonsProfile.length != numDetectors) {
            throw new IllegalArgumentException("Profile array must match numDetectors length.");
        }
        this.incidentPhotonsPerRay = incidentPhotonsProfile.clone();
    }

    /** Perfil flat (sem Bowtie). */
    public void setIncidentPhotonsPerRay(double constantPhotons) {
        Arrays.fill(this.incidentPhotonsPerRay, constantPhotons);
    }

    public void setNormalizationConstant(double c) { this.normalizationConstant = c; }
    
    /** C atual (Eq. 5). Util para VarianceMapCalibrator preservar/restaurar o valor. */
    public double getNormalizationConstant() { return normalizationConstant; }


    public void setForwardProjectionStepMM(double stepMM) {
        if (stepMM <= 0) throw new IllegalArgumentException("stepMM must be positive");
        this.forwardProjectionStepMM = stepMM;
    }

    public void setBackprojectionUsesL4(boolean usesL4) { this.backprojectionUsesL4 = usesL4; }

    public int getNumViews() { return numViews; }
    public int getNumDetectors() { return numDetectors; }
    public double[] getFanAnglesRad() { return fanAnglesRad.clone(); }
    public double getSourceToDetectorDistanceMM() { return sourceToDetectorDistanceMM; }

    // =====================================================================
    // Auxiliares de geometria
    // =====================================================================

    public static double[] equiangularFanAngles(int numDetectors, double angleSpacingRad, double offset) {
        double[] g = new double[numDetectors];
        double center = ((numDetectors - 1) / 2.0) + offset;
        for (int i = 0; i < numDetectors; i++) {
            g[i] = (i - center) * angleSpacingRad;
        }
        return g;
    }

    public static double[] equispacedFanAngles(int numDetectors, double detectorSpacingMM,
                                                double sourceToDetectorDistanceMM) {
        double[] g = new double[numDetectors];
        double center = (numDetectors - 1) / 2.0;
        for (int i = 0; i < numDetectors; i++) {
            double u = (i - center) * detectorSpacingMM;
            g[i] = Math.atan(u / sourceToDetectorDistanceMM);
        }
        return g;
    }

    public static double[] ramLakKernelEquiangular(int numTaps, double deltaGammaRad) {
        if (numTaps % 2 == 0) numTaps++;
        int half = numTaps / 2;
        double[] h = new double[numTaps];
        for (int n = -half; n <= half; n++) {
            int idx = n + half;
            if (n == 0) {
                h[idx] = 1.0 / (8.0 * deltaGammaRad * deltaGammaRad);
            } else if (n % 2 == 0) {
                h[idx] = 0.0;
            } else {
                double s = Math.sin(n * deltaGammaRad);
                h[idx] = -1.0 / (2.0 * Math.PI * Math.PI * s * s);
            }
        }
        return h;
    }

    // =====================================================================
    // Execucao principal
    // =====================================================================

    public ImagePlus computeVarianceMap(ImagePlus ctStack) {
        ImageStack inStack = ctStack.getStack();
        int width = inStack.getWidth();
        int height = inStack.getHeight();
        int nSlices = inStack.getSize();

        ImageStack outStack = new ImageStack(width, height);
        for (int s = 1; s <= nSlices; s++) {
            ImageProcessor ip = inStack.getProcessor(s);
            FloatProcessor fp = ip.convertToFloatProcessor();
            float[] huPixels = (float[]) fp.getPixels();

            float[] varianceMap = computeVarianceMapForSlice(huPixels, width, height);
            outStack.addSlice("varianceMap_" + s, new FloatProcessor(width, height, varianceMap, null));
        }
        return new ImagePlus(ctStack.getTitle() + "_varianceMap", outStack);
    }

    /**
     * Roda apenas Eqs. (1) e (5) sobre um sinograma de variancia (sigma_p^2) ja
     * calculado externamente (ex.: no pipeline de NPS), pulando o forward projector
     * water-equivalent embutido.
     *
     * @param sigmaP2 dimensionado [numViews][numDetectors].
     */
    public float[] computeVarianceMapFromProjectionVariance(double[][] sigmaP2, int width, int height) {
        if (sigmaP2 == null || sigmaP2.length != numViews || sigmaP2[0].length != numDetectors) {
            throw new IllegalArgumentException("sigmaP2 must be sized [numViews][numDetectors] = ["
                    + numViews + "][" + numDetectors + "]");
        }
        double[][] sigmaF2 = filterProjectionVariance(sigmaP2);
        return backprojectVariance(sigmaF2, width, height);
    }

    private float[] computeVarianceMapForSlice(float[] huPixels, int width, int height) {
        double[][] pathLengthMM = forwardProjectWaterEquivalentPath(huPixels, width, height);
        double[][] sigmaP2 = computeProjectionVariance(pathLengthMM);
        double[][] sigmaF2 = filterProjectionVariance(sigmaP2);
        return backprojectVariance(sigmaF2, width, height);
    }

    // =====================================================================
    // Etapas (paralelizadas)
    // =====================================================================

    private double[][] forwardProjectWaterEquivalentPath(float[] hu, int width, int height) {
        double[][] pathLenMM = new double[numViews][numDetectors];

        double halfDiagMM = 0.5 * Math.sqrt((double) (width * width + height * height)) * pixelSpacingMM;
        double margin = 2.0 * pixelSpacingMM;
        // tStart/tEnd globais (corretos p/ o raio central; fora do FOV o HU=-1000 nao acrescenta path)
        double tStart = Math.max(0.0, sourceToIsocenterDistanceMM - halfDiagMM - margin);
        double tEnd = sourceToIsocenterDistanceMM + halfDiagMM + margin;
        double step = forwardProjectionStepMM;
        int nSteps = (int) Math.ceil((tEnd - tStart) / step);

        IntStream.range(0, numViews).parallel().forEach(v -> {
            double phi = v * 2.0 * Math.PI / numViews;
            double sx = sourceToIsocenterDistanceMM * Math.sin(phi);
            double sy = -sourceToIsocenterDistanceMM * Math.cos(phi);

            for (int i = 0; i < numDetectors; i++) {
                double gamma = fanAnglesRad[i];
                // Direcao do raio (convencao consistente com o backprojector):
                double dirx = Math.sin(gamma - phi);
                double diry = Math.cos(gamma - phi);

                double sum = 0.0;
                double t = tStart;
                for (int k = 0; k < nSteps; k++) {
                    double x = sx + t * dirx;
                    double y = sy + t * diry;
                    double px = x / pixelSpacingMM + (width - 1) / 2.0;
                    double py = y / pixelSpacingMM + (height - 1) / 2.0;
                    double huVal = bilinearSampleHU(hu, width, height, px, py);
                    sum += (1.0 + huVal / 1000.0) * step;
                    t += step;
                }
                pathLenMM[v][i] = Math.max(0.0, sum);
            }
        });
        return pathLenMM;
    }

    private static double bilinearSampleHU(float[] hu, int width, int height, double px, double py) {
        if (px < 0 || py < 0 || px > width - 1 || py > height - 1) return -1000.0;
        int x0 = (int) Math.floor(px);
        int y0 = (int) Math.floor(py);
        int x1 = Math.min(x0 + 1, width - 1);
        int y1 = Math.min(y0 + 1, height - 1);
        double fx = px - x0;
        double fy = py - y0;
        double v00 = hu[y0 * width + x0];
        double v10 = hu[y0 * width + x1];
        double v01 = hu[y1 * width + x0];
        double v11 = hu[y1 * width + x1];
        double v0 = v00 * (1 - fx) + v10 * fx;
        double v1 = v01 * (1 - fx) + v11 * fx;
        return v0 * (1 - fy) + v1 * fy;
    }

    private double[][] computeProjectionVariance(double[][] pathLengthMM) {
        int nE = spectrumEnergyKeV.length;
        double weightSum = 0.0;
        for (double w : spectrumWeight) weightSum += w;

        double[][] n0 = new double[numDetectors][nE];
        for (int i = 0; i < numDetectors; i++) {
            for (int k = 0; k < nE; k++) {
                n0[i][k] = incidentPhotonsPerRay[i] * spectrumWeight[k] / weightSum;
            }
        }

        double[][] sigmaP2 = new double[numViews][numDetectors];

        IntStream.range(0, numViews).parallel().forEach(v -> {
            for (int i = 0; i < numDetectors; i++) {
                double pathCM = pathLengthMM[v][i] / 10.0;
                double numer = 0.0;
                double denom = 0.0;
                for (int k = 0; k < nE; k++) {
                    double atten = muWaterPerCm[k] * pathCM;
                    double nk = n0[i][k] * Math.exp(-atten);
                    double ek = spectrumEnergyKeV[k];
                    numer += nk * ek * ek;
                    denom += nk * ek;
                }
                sigmaP2[v][i] = (denom > 0.0) ? numer / (denom * denom) : 0.0; // Eqs. (2)-(4)
            }
        });
        return sigmaP2;
    }

    private double[][] filterProjectionVariance(double[][] sigmaP2) {
        int kLen = kernelH.length;
        int half = kLen / 2;
        double[] h2 = new double[kLen];
        for (int k = 0; k < kLen; k++) h2[k] = kernelH[k] * kernelH[k];

        double[] geomWeight = new double[numDetectors];
        double dSquared = sourceToIsocenterDistanceMM * sourceToIsocenterDistanceMM;
        for (int i = 0; i < numDetectors; i++) {
            double c = Math.cos(fanAnglesRad[i]);
            geomWeight[i] = dSquared * c * c;
        }

        // Quadratura discreta da integral angular da Eq. (1).
        final double deltaGammaRad = Math.abs(fanAnglesRad[1] - fanAnglesRad[0]);

        double[][] sigmaF2 = new double[numViews][numDetectors];

        IntStream.range(0, numViews).parallel().forEach(v -> {
            double[] weighted = new double[numDetectors];
            for (int j = 0; j < numDetectors; j++) {
                weighted[j] = geomWeight[j] * sigmaP2[v][j];
            }
            for (int i = 0; i < numDetectors; i++) {
                double acc = 0.0;
                int jStart = Math.max(0, i - half);
                int jEnd = Math.min(numDetectors - 1, i + half);
                for (int j = jStart; j <= jEnd; j++) {
                    acc += h2[(i - j) + half] * weighted[j];
                }
                sigmaF2[v][i] = acc * deltaGammaRad;
            }
        });
        return sigmaF2;
    }

    private float[] backprojectVariance(double[][] sigmaF2, int width, int height) {
        float[] result = new float[width * height];
        double gammaMin = fanAnglesRad[0];
        double gammaMax = fanAnglesRad[numDetectors - 1];
        double gammaSpacing = (gammaMax - gammaMin) / (numDetectors - 1);

        double[] sinPhi = new double[numViews];
        double[] cosPhi = new double[numViews];
        for (int v = 0; v < numViews; v++) {
            double phi = v * 2.0 * Math.PI / numViews;
            sinPhi[v] = Math.sin(phi);
            cosPhi[v] = Math.cos(phi);
        }

        IntStream.range(0, height).parallel().forEach(py -> {
            double y = (py - (height - 1) / 2.0) * pixelSpacingMM;

            for (int px = 0; px < width; px++) {
                double x = (px - (width - 1) / 2.0) * pixelSpacingMM;
                double pixelSum = 0.0;

                for (int v = 0; v < numViews; v++) {
                    double u = x * cosPhi[v] + y * sinPhi[v];
                    double vv = sourceToIsocenterDistanceMM + y * cosPhi[v] - x * sinPhi[v];
                    double l2 = u * u + vv * vv; // distancia euclidiana exata fonte->pixel (verificado)
                    double gamma = Math.atan2(u, vv);

                    double fIdx = (gamma - gammaMin) / gammaSpacing;
                    if (fIdx < 0 || fIdx > numDetectors - 1) continue;

                    int i0 = (int) Math.floor(fIdx);
                    int i1 = Math.min(i0 + 1, numDetectors - 1);
                    double frac = fIdx - i0;
                    double val = sigmaF2[v][i0] * (1 - frac) + sigmaF2[v][i1] * frac;

                    double weight = backprojectionUsesL4 ? 1.0 / (l2 * l2) : 1.0 / l2;
                    pixelSum += (weight * val); // Eq. (5)
                }
                result[py * width + px] = (float) pixelSum;
            }
        });

        float c2 = (float) (normalizationConstant * normalizationConstant);
        for (int idx = 0; idx < result.length; idx++) {
            result[idx] *= c2;
        }
        return result;
    }
}
