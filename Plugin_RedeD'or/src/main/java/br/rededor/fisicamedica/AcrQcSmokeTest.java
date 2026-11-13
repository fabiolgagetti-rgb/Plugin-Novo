package br.rededor.fisicamedica;

import java.util.List;

import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.FloatProcessor;

/**
 * Teste de fumaca (smoke test) determinístico para a logica geometrica do
 * motor de CQ ({@link AcrQcAnalyser}), sem depender de imagens DICOM reais.
 *
 * Constroi um stack sintético que imita a estrutura longitudinal do phantom
 * ACR (corpo cilindrico de area ~31400 mm2 ao longo de todas as fatias, com um
 * bloco central contendo insertos de Ar e Osso no "Modulo 1") e verifica:
 *   - findPhantomLimits: detecta o corpo em todas as fatias.
 *   - findModule1Slices: identifica apenas as fatias com Ar+Osso.
 *   - filterUniformitySlices: aprova fatias uniformes e reprova fatias com bead.
 *   - countPeaks (via measureSliceThickness indireto nao e testado aqui; a
 *     contagem de picos e validada por um perfil sintético direto).
 *
 * NAO faz parte do plugin em runtime; e um utilitario de verificacao com main().
 */
public class AcrQcSmokeTest {

    static final int W = 256, H = 256;
    static final double PX = 0.5; // mm/pixel -> fantoma de 200mm cabe em 400px... usamos raio em mm

    public static void main(String[] args) {
        int failures = 0;
        failures += testPhantomLimitsAndModule1();
        failures += testUniformityFilter();
        failures += testCountPeaks();

        System.out.println("\n==== RESUMO ====");
        if (failures == 0) System.out.println("TODOS OS TESTES PASSARAM.");
        else System.out.println(failures + " TESTE(S) FALHARAM.");
    }

    private static Calibration cal() {
        Calibration c = new Calibration();
        c.pixelWidth = PX;
        c.pixelHeight = PX;
        c.setUnit("mm");
        return c;
    }

    /** Preenche um disco centrado com dado valor HU; fora do disco fica em bgHU. */
    private static FloatProcessor cylinderSlice(double radiusMm, double bodyHU, double bgHU) {
        FloatProcessor fp = new FloatProcessor(W, H);
        double cx = W / 2.0, cy = H / 2.0;
        double rPx = radiusMm / PX;
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                double d = Math.hypot(x - cx, y - cy);
                fp.setf(x, y, (float) (d <= rPx ? bodyHU : bgHU));
            }
        return fp;
    }

    private static void addDisk(FloatProcessor fp, double offXmm, double offYmm, double rMm, double hu) {
        double cx = W / 2.0 + offXmm / PX;
        double cy = H / 2.0 + offYmm / PX;
        double rPx = rMm / PX;
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++)
                if (Math.hypot(x - cx, y - cy) <= rPx) fp.setf(x, y, (float) hu);
    }

    // Area alvo ~31400 mm2 -> raio ~100mm. Com W=256,PX=0.5 => 128px de meia largura
    // => raio maximo ~64mm (nao cabe 100mm). Ajustamos para um raio que caiba e
    // caia na janela de area aceita (15000..45000 mm2): raio 90mm -> area 25447 mm2.
    static final double BODY_RADIUS_MM = 90.0;

    private static int testPhantomLimitsAndModule1() {
        System.out.println("== Teste 1: limites do phantom + Modulo 1 ==");
        int fails = 0;
        ImageStack stack = new ImageStack(W, H);
        int nSlices = 20;
        int mod1Start = 8, mod1End = 12; // fatias 8..12 contem os insertos
        for (int i = 1; i <= nSlices; i++) {
            FloatProcessor fp = cylinderSlice(BODY_RADIUS_MM, 0.0, -1000.0); // corpo em agua
            if (i >= mod1Start && i <= mod1End) {
                addDisk(fp, -30, 0, 12, -1000.0); // Ar
                addDisk(fp,  30, 0, 12,  900.0);  // Osso
            }
            stack.addSlice(fp);
        }

        Calibration c = cal();
        double area = Math.PI * BODY_RADIUS_MM * BODY_RADIUS_MM;
        System.out.printf("  area do corpo = %.0f mm2 (janela 15000..45000)%n", area);

        int[] lim = AcrQcAnalyser.findPhantomLimits(stack, c, PX, PX);
        System.out.printf("  limites detectados: %d..%d (esperado 1..%d)%n", lim[0], lim[1], nSlices);
        if (lim[0] != 1 || lim[1] != nSlices) { System.out.println("  FALHOU: limites"); fails++; }

        List<Integer> mod1 = AcrQcAnalyser.findModule1Slices(stack, lim[0], lim[1], c, PX, PX);
        System.out.println("  Modulo 1 detectado nas fatias: " + mod1 + " (esperado 8..12)");
        boolean ok = mod1.size() == 5 && mod1.get(0) == mod1Start && mod1.get(mod1.size() - 1) == mod1End;
        if (!ok) { System.out.println("  FALHOU: Modulo 1"); fails++; }
        if (fails == 0) System.out.println("  OK");
        return fails;
    }

    private static int testUniformityFilter() {
        System.out.println("== Teste 2: filtro de uniformidade (beads) ==");
        int fails = 0;
        ImageStack stack = new ImageStack(W, H);
        // fatia 1: uniforme (agua). fatia 2: com bead (metal 1500 HU).
        FloatProcessor clean = cylinderSlice(BODY_RADIUS_MM, 0.0, -1000.0);
        FloatProcessor withBead = cylinderSlice(BODY_RADIUS_MM, 0.0, -1000.0);
        addDisk(withBead, 50, 0, 2, 1500.0);
        stack.addSlice(clean);
        stack.addSlice(withBead);

        List<Integer> valid = AcrQcAnalyser.filterUniformitySlices(stack, 1, 2, cal(), PX, PX);
        System.out.println("  fatias aprovadas: " + valid + " (esperado apenas [1])");
        if (!(valid.size() == 1 && valid.get(0) == 1)) { System.out.println("  FALHOU"); fails++; }
        else System.out.println("  OK");
        return fails;
    }

    private static int testCountPeaks() {
        System.out.println("== Teste 3: contagem de picos ==");
        int fails = 0;
        // Perfil com 5 picos bem separados (acima da meia-altura).
        // pixel size 0.5mm, separacao > 3mm -> picos a cada ~10 amostras.
        int n = 100;
        double[] prof = new double[n];
        double maxV = 100.0;
        int[] peakCenters = {10, 30, 50, 70, 90};
        for (int i = 0; i < n; i++) prof[i] = 0;
        for (int pc : peakCenters) {
            for (int k = -2; k <= 2; k++) {
                int idx = pc + k;
                if (idx >= 0 && idx < n) prof[idx] = maxV;
            }
        }
        int peaks = invokeCountPeaks(prof, maxV, PX);
        System.out.println("  picos contados: " + peaks + " (esperado 5)");
        if (peaks != 5) { System.out.println("  FALHOU"); fails++; }
        else System.out.println("  OK");
        return fails;
    }

    // countPeaks e privado em AcrQcAnalyser; replicamos a chamada via reflexao
    // para validar a implementacao real sem expor a API.
    private static int invokeCountPeaks(double[] profile, double maxV, double pixelSizeMM) {
        try {
            java.lang.reflect.Method m = AcrQcAnalyser.class.getDeclaredMethod(
                    "countPeaks", double[].class, double.class, double.class);
            m.setAccessible(true);
            return (int) m.invoke(null, profile, maxV, pixelSizeMM);
        } catch (Exception e) {
            System.out.println("  (nao foi possivel invocar countPeaks: " + e.getMessage() + ")");
            return -1;
        }
    }
}
