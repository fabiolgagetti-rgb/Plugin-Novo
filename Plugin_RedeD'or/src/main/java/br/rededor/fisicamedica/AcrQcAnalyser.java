package br.rededor.fisicamedica;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.OvalRoi;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.measure.Measurements;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;

/**
 * Motor de Controle de Qualidade (CQ) do phantom ACR de Tomografia.
 *
 * Executa de forma automatizada os testes exigidos no controle de qualidade
 * (testes ANVISA) a partir de uma serie DICOM carregada:
 *
 *   - Exatidao do numero de CT (TC): agua, osso, ar (interno), polietileno,
 *     acrilico e ar no fundo (externo).
 *   - Uniformidade e ruido (Modulo 3): centro + 4 perifericos (12h/3h/6h/9h).
 *   - Baixo contraste / CNR (Modulo 2).
 *   - Espessura de corte (rampas do Modulo 1, contagem de picos).
 *   - Exatidao da distancia entre os pinos de referencia (Modulo de uniformidade).
 *
 * A localizacao geometrica dos modulos reaproveita a mesma estrategia validada
 * em {@link AcrAutoDetectability} (limites do phantom por area e assinatura de
 * densidade Ar+Osso do Modulo 1).
 *
 * Observacao importante sobre a fatia do Modulo 1: um dos lados da secao de
 * insertos normalmente contem um parafuso/BB que gera artefatos. Por isso a
 * exatidao do numero de CT e medida na fatia EXTREMA do Modulo 1 (primeira ou
 * ultima da secao) que apresentar o MELHOR resultado (menor artefato), evitando
 * o lado comprometido.
 */
public class AcrQcAnalyser {

    // ------------------------------------------------------------------
    // Estruturas de resultado
    // ------------------------------------------------------------------

    /** Medida media +/- desvio padrao de uma regiao (HU). */
    public static class Measure {
        public String label;
        public double mean;
        public double sd;
        public boolean valid;

        public Measure(String label) { this.label = label; this.valid = false; }
        public Measure(String label, double mean, double sd) {
            this.label = label; this.mean = mean; this.sd = sd; this.valid = true;
        }
    }

    /** Resultado completo da analise de CQ. */
    public static class AcrQcResult {
        public boolean found = false;
        public String message = "";

        // Limites e geometria detectada
        public int phantomStart = -1, phantomEnd = -1;   // 1-based (indice de fatia no stack)
        public int mod1Center = -1;                       // fatia central do Modulo 1
        public int ctSlice = -1;                          // fatia usada p/ exatidao do nº CT
        public int uniformitySlice = -1;                  // fatia usada p/ uniformidade (Modulo 3)
        public int lowContrastSlice = -1;                 // fatia usada p/ baixo contraste (Modulo 2)

        // Exatidao do numero de CT
        public Measure water    = new Measure("Agua");
        public Measure bone     = new Measure("Osso");
        public Measure airIn    = new Measure("Ar (interno)");
        public Measure airOut   = new Measure("Ar (fundo/externo)");
        public Measure poly     = new Measure("Polietileno");
        public Measure acrylic  = new Measure("Acrilico");

        // Uniformidade (Modulo 3) + ruido
        public Measure uCenter = new Measure("Centro");
        public Measure uTop    = new Measure("Topo (12h)");
        public Measure uBottom = new Measure("Fundo (6h)");
        public Measure uLeft   = new Measure("Esquerda (9h)");
        public Measure uRight  = new Measure("Direita (3h)");
        public double maxUniformityDiff = Double.NaN; // maior |periferico - centro|
        public double noise = Double.NaN;             // ruido = SD do ROI central

        // Baixo contraste / CNR (Modulo 2)
        public Measure lcSignal = new Measure("Massa (sinal)");
        public Measure lcBackground = new Measure("Fundo");
        public double cnr = Double.NaN;

        // Espessura de corte
        public double sliceThicknessDicom = Double.NaN;  // valor nominal (DICOM 0018,0050)
        public double sliceThicknessTop = Double.NaN;    // medido rampas superiores (mm)
        public double sliceThicknessBottom = Double.NaN; // medido rampas inferiores (mm)
        public String sliceThicknessNote = "";           // aviso de confiabilidade

        // Exatidao de distancia entre pinos (mm)
        public int pinSlice = -1;
        public double pinDistance = Double.NaN;      // distancia do par escolhido
        public double pinDistanceH = Double.NaN;     // par mais horizontal
        public double pinDistanceV = Double.NaN;     // par mais vertical
        public int pinMarkerCount = 0;               // nº de marcadores detectados
        public String pinOrientation = "N/A";        // orientacao do par escolhido
        public boolean pinPhantomInverted = false;   // conferencia de inversao
        public String pinNote = "";
        public static final double PIN_DISTANCE_NOMINAL_MM = 100.0;

        // Linha unica para colar no Excel (TAB-separada, decimal com virgula)
        public String excelLine = "";
    }

    // ------------------------------------------------------------------
    // Parametros geometricos do phantom ACR
    // ------------------------------------------------------------------

    private static final double PHANTOM_AREA_MIN_MM2 = 15000.0;
    private static final double PHANTOM_AREA_MAX_MM2 = 45000.0;
    private static final double PHANTOM_HU_FLOOR     = -500.0;

    private static final double CT_ROI_RADIUS_MM     = 8.0;   // raio do ROI de medida de HU dos materiais
    private static final double UNIF_ROI_RADIUS_MM   = 10.0;  // raio do ROI de uniformidade
    private static final double UNIF_PERIPHERAL_MM   = 80.0;  // distancia centro->periferico (uniformidade)
    private static final double AIR_OUT_RADIUS_MM    = 25.0;  // raio do ROI de ar externo (fundo)

    // ------------------------------------------------------------------
    // Carregamento da serie em stack (com calibracao)
    // ------------------------------------------------------------------

    static class LoadedSeries {
        ImageStack stack;
        Calibration cal;
        double px, py;
    }

    private static LoadedSeries loadSeries(SeriesData sd) {
        LoadedSeries ls = new LoadedSeries();
        ImageStack stack = new ImageStack();
        Calibration cal = null;
        for (File f : sd.getFiles()) {
            ImagePlus imp = new ImagePlus(f.getAbsolutePath());
            if (imp.getWidth() == 0) continue;
            if (cal == null) cal = imp.getCalibration();
            stack.addSlice(imp.getProcessor());
        }
        ls.stack = stack;
        ls.cal = cal;
        ls.px = (cal != null && cal.pixelWidth > 0) ? cal.pixelWidth : 0.5;
        ls.py = (cal != null && cal.pixelHeight > 0) ? cal.pixelHeight : 0.5;
        return ls;
    }

    // ------------------------------------------------------------------
    // Localizacao do phantom e do Modulo 1
    // ------------------------------------------------------------------

    /** Limites (1-based, inclusivos) das fatias que contem o corpo do phantom. */
    static int[] findPhantomLimits(ImageStack stack, Calibration cal, double px, double py) {
        int start = -1, end = -1;
        double pixelArea = px * py;
        for (int i = 1; i <= stack.getSize(); i++) {
            ImageProcessor ip = stack.getProcessor(i);
            int count = 0;
            int n = ip.getPixelCount();
            for (int p = 0; p < n; p++) {
                double hu = (cal != null) ? cal.getCValue(ip.getf(p)) : ip.getf(p);
                if (hu > PHANTOM_HU_FLOOR) count++;
            }
            double areaMM2 = count * pixelArea;
            if (areaMM2 > PHANTOM_AREA_MIN_MM2 && areaMM2 < PHANTOM_AREA_MAX_MM2) {
                if (start == -1) start = i;
                end = i;
            }
        }
        return new int[]{start, end};
    }

    /**
     * Fatias do Modulo 1: assinatura de densidade com Ar (&lt; -700 HU) e
     * Osso (&gt; 700 HU) simultaneos dentro de um ROI central.
     */
    static List<Integer> findModule1Slices(ImageStack stack, int zStart, int zEnd,
                                            Calibration cal, double px, double py) {
        List<Integer> slices = new ArrayList<>();
        int w = stack.getWidth();
        int h = stack.getHeight();
        double rx = 70.0 / px;
        double ry = 70.0 / py;
        Roi centerRoi = new OvalRoi((w / 2.0) - rx, (h / 2.0) - ry, rx * 2, ry * 2);

        for (int i = zStart; i <= zEnd; i++) {
            ImageProcessor ip = stack.getProcessor(i);
            ip.setRoi(centerRoi);
            ImageStatistics stats = ImageStatistics.getStatistics(ip);
            double minHU = (cal != null) ? cal.getCValue(stats.min) : stats.min;
            double maxHU = (cal != null) ? cal.getCValue(stats.max) : stats.max;
            if (minHU < -700 && maxHU > 700) slices.add(i);
        }
        return slices;
    }

    // ------------------------------------------------------------------
    // Centro do phantom numa fatia (centroide do corpo com HU > -200)
    // ------------------------------------------------------------------

    static double[] phantomCenter(ImageProcessor ip, Calibration cal) {
        double sumX = 0, sumY = 0;
        long count = 0;
        int w = ip.getWidth(), h = ip.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double hu = (cal != null) ? cal.getCValue(ip.getf(x, y)) : ip.getf(x, y);
                if (hu > -200) { sumX += x; sumY += y; count++; }
            }
        }
        if (count == 0) return new double[]{w / 2.0, h / 2.0};
        return new double[]{sumX / count, sumY / count};
    }

    private static Measure measureOval(ImageProcessor ip, Calibration cal, double cx, double cy,
                                       double radiusPx, String label) {
        OvalRoi roi = new OvalRoi(cx - radiusPx, cy - radiusPx, radiusPx * 2, radiusPx * 2);
        ip.setRoi(roi);
        ImageStatistics st = ImageStatistics.getStatistics(ip, Measurements.MEAN + Measurements.STD_DEV, cal);
        ip.resetRoi();
        return new Measure(label, st.mean, st.stdDev);
    }

    // ------------------------------------------------------------------
    // Classificacao de fatias por conteudo
    //
    // A estimativa puramente geometrica por offset de modulo nao e confiavel
    // (os limites do phantom incluem fatias parciais e a ordem pode estar
    // invertida). Classificamos cada fatia do corpo do phantom pelo seu
    // conteudo dentro de um ROI central:
    //   - INSERTS   : contem Ar (< -700) e Osso (> 700) -> Modulo 1
    //   - BEADS     : contem marcadores de alto HU a ~50-70mm do centro
    //                 (modulo de uniformidade com beads/pinos de referencia)
    //   - LOW_CONTR : fatia uniforme com pequenas estruturas de baixo contraste
    //   - UNIFORM   : fatia uniforme e limpa (ideal para NPS)
    // ------------------------------------------------------------------

    enum SliceKind { INSERTS, BEADS, UNIFORM, LOW_CONTRAST, OTHER }

    /** Classifica uma fatia pelo conteudo de um ROI central de 160mm de diametro. */
    static SliceKind classifySlice(ImageStack stack, int z, Calibration cal, double px, double py) {
        ImageProcessor ip = stack.getProcessor(z);
        int w = stack.getWidth(), h = stack.getHeight();

        // Gate de area: a fatia deve conter um corpo cilindrico completo (area
        // do phantom ~31400 mm2). Fatias parciais/fora do phantom sao OTHER.
        int bodyCount = 0;
        int nPix = ip.getPixelCount();
        for (int p = 0; p < nPix; p++) {
            double hu = (cal != null) ? cal.getCValue(ip.getf(p)) : ip.getf(p);
            if (hu > PHANTOM_HU_FLOOR) bodyCount++;
        }
        double bodyAreaMM2 = bodyCount * px * py;
        if (bodyAreaMM2 < 25000) return SliceKind.OTHER; // corpo incompleto

        // ROI central de 140mm p/ assinatura de insertos (Ar+Osso).
        double rx = 70.0 / px, ry = 70.0 / py;
        Roi centerRoi = new OvalRoi((w / 2.0) - rx, (h / 2.0) - ry, rx * 2, ry * 2);
        ip.setRoi(centerRoi);
        ImageStatistics cs = ImageStatistics.getStatistics(ip);
        ip.resetRoi();
        double minHU = (cal != null) ? cal.getCValue(cs.min) : cs.min;
        double maxHU = (cal != null) ? cal.getCValue(cs.max) : cs.max;

        if (minHU < -700 && maxHU > 700) return SliceKind.INSERTS;

        // Conta pixels de alto HU (beads) num anel de 40-80mm do centro.
        double[] c = phantomCenter(ip, cal);
        int beadCount = 0;
        double rInner = 40.0 / px, rOuter = 80.0 / px;
        int x0 = (int) Math.max(0, c[0] - rOuter), x1 = (int) Math.min(w - 1, c[0] + rOuter);
        int y0 = (int) Math.max(0, c[1] - rOuter), y1 = (int) Math.min(h - 1, c[1] + rOuter);
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                double d = Math.hypot(x - c[0], y - c[1]);
                if (d < rInner || d > rOuter) continue;
                double hu = (cal != null) ? cal.getCValue(ip.getf(x, y)) : ip.getf(x, y);
                if (hu > 300) beadCount++;
            }
        }
        if (beadCount > 50) return SliceKind.BEADS;

        // HU medio central (ROI de 20mm): distingue agua (~0 HU) do material de
        // baixo contraste (~+90 HU no ACR).
        double rc = 10.0 / px;
        OvalRoi cRoi = new OvalRoi(c[0] - rc, c[1] - rc, rc * 2, rc * 2);
        ip.setRoi(cRoi);
        ImageStatistics ctr = ImageStatistics.getStatistics(ip, Measurements.MEAN, cal);
        ip.resetRoi();
        double centerHU = ctr.mean;

        // Modulo de baixo contraste: uniforme mas com fundo elevado (~+50..+150 HU).
        if (centerHU > 50 && centerHU < 200) return SliceKind.LOW_CONTRAST;
        // Modulo de uniformidade (agua): HU central proximo de zero.
        if (Math.abs(centerHU) < 30) return SliceKind.UNIFORM;
        return SliceKind.OTHER;
    }

    /** Agrupa indices contiguos (no espaco de fatias ordenadas) em segmentos. */
    static List<int[]> contiguousSegments(List<Integer> sorted) {
        List<int[]> segs = new ArrayList<>();
        if (sorted.isEmpty()) return segs;
        int start = sorted.get(0), prev = start;
        for (int i = 1; i < sorted.size(); i++) {
            int v = sorted.get(i);
            if (v == prev + 1) { prev = v; }
            else { segs.add(new int[]{start, prev}); start = v; prev = v; }
        }
        segs.add(new int[]{start, prev});
        return segs;
    }

    // ------------------------------------------------------------------
    // Metodo principal
    // ------------------------------------------------------------------

    public static AcrQcResult analyze(SeriesData sd) {
        AcrQcResult r = new AcrQcResult();
        if (sd == null || sd.getFiles().isEmpty()) {
            r.message = "Nenhuma serie ativa. Abra as imagens do phantom ACR no File Browser.";
            return r;
        }

        LoadedSeries ls = loadSeries(sd);
        if (ls.stack.getSize() == 0) {
            r.message = "Nao foi possivel carregar as imagens da serie.";
            return r;
        }

        int[] limits = findPhantomLimits(ls.stack, ls.cal, ls.px, ls.py);
        r.phantomStart = limits[0];
        r.phantomEnd = limits[1];
        if (r.phantomStart == -1 || r.phantomEnd == -1) {
            r.message = "Phantom nao localizado (area cilindrica insuficiente).";
            return r;
        }

        // Classifica todas as fatias do corpo do phantom por conteudo.
        ModuleMap mm = mapModules(ls, r.phantomStart, r.phantomEnd);
        if (mm.insertSlices.isEmpty()) {
            r.message = "Modulo 1 nao encontrado (insertos de Ar e Osso ausentes).";
            return r;
        }
        r.mod1Center = mm.insertSlices.get(mm.insertSlices.size() / 2);

        // Modulo de uniformidade (liso): centro do maior bloco UNIFORM.
        int mod3Center = mm.uniformCenter;
        if (mod3Center < 1) {
            mod3Center = mm.beadsCenter > 0 ? mm.beadsCenter : r.mod1Center;
        }

        // Baixo contraste: centro do bloco classificado como LOW_CONTRAST
        // (fundo elevado ~+90 HU no ACR). Fallback: uniforme adjacente aos inserts.
        int mod2Center = mm.lowContrastCenter;
        if (mod2Center < 1) mod2Center = nearestUniformToInserts(mm, r.mod1Center);
        if (mod2Center < 1) mod2Center = mod3Center;

        r.uniformitySlice = mod3Center;
        r.lowContrastSlice = mod2Center;

        // Espessura de corte nominal (DICOM)
        r.sliceThicknessDicom = readSliceThicknessDicom(sd);

        // --- 1. Exatidao do numero de CT (fatia extrema sem parafuso) ---
        measureCtNumbers(ls, r, mm.insertSlices);

        // --- 2. Uniformidade + ruido (Modulo 3) ---
        measureUniformity(ls, r, mod3Center);

        // --- 3. Baixo contraste / CNR (Modulo 2) ---
        measureLowContrast(ls, r, mod2Center);

        // --- 4. Espessura de corte (rampas do Modulo 1) ---
        measureSliceThickness(ls, r);

        // --- 5. Exatidao de distancia entre pinos de referencia ---
        // Os pinos/beads ficam no modulo de uniformidade COM beads.
        int pinSlice = (mm.beadsCenter > 0) ? mm.beadsCenter : mod3Center;
        r.pinSlice = pinSlice;
        measurePinDistance(ls, r, pinSlice);

        r.found = true;
        r.excelLine = buildExcelLine(r);
        return r;
    }

    // ------------------------------------------------------------------
    // Mapa dos modulos por conteudo
    // ------------------------------------------------------------------

    static class ModuleMap {
        List<Integer> insertSlices = new ArrayList<>();
        List<Integer> uniformSlices = new ArrayList<>();
        List<Integer> beadsSlices = new ArrayList<>();
        List<Integer> lowContrastSlices = new ArrayList<>();
        int insertCenter = -1, uniformCenter = -1, beadsCenter = -1, lowContrastCenter = -1;
    }

    private static int centerOfLargestSegment(List<Integer> slices) {
        if (slices.isEmpty()) return -1;
        List<Integer> sorted = new ArrayList<>(slices);
        Collections.sort(sorted);
        List<int[]> segs = contiguousSegments(sorted);
        int[] largest = segs.get(0);
        for (int[] s : segs) if ((s[1] - s[0]) > (largest[1] - largest[0])) largest = s;
        return (largest[0] + largest[1]) / 2;
    }

    private static ModuleMap mapModules(LoadedSeries ls, int zStart, int zEnd) {
        ModuleMap mm = new ModuleMap();
        for (int z = zStart; z <= zEnd; z++) {
            SliceKind k = classifySlice(ls.stack, z, ls.cal, ls.px, ls.py);
            switch (k) {
                case INSERTS:      mm.insertSlices.add(z); break;
                case BEADS:        mm.beadsSlices.add(z); break;
                case UNIFORM:      mm.uniformSlices.add(z); break;
                case LOW_CONTRAST: mm.lowContrastSlices.add(z); break;
                default: break;
            }
        }
        mm.insertCenter      = centerOfLargestSegment(mm.insertSlices);
        mm.uniformCenter     = centerOfLargestSegment(mm.uniformSlices);
        mm.beadsCenter       = centerOfLargestSegment(mm.beadsSlices);
        mm.lowContrastCenter = centerOfLargestSegment(mm.lowContrastSlices);
        return mm;
    }

    /** Fatia uniforme mais proxima do Modulo 1, afastada ~1 modulo das bordas. */
    private static int nearestUniformToInserts(ModuleMap mm, int insertCenter) {
        if (mm.uniformSlices.isEmpty() || insertCenter < 1) return -1;
        // A fatia uniforme a ~8 fatias (um modulo) do centro dos inserts e a
        // melhor candidata ao modulo de baixo contraste adjacente; na ausencia,
        // pega a uniforme mais proxima dos inserts.
        int best = -1, bestDist = Integer.MAX_VALUE;
        for (int z : mm.uniformSlices) {
            int dist = Math.abs(z - insertCenter);
            if (dist < bestDist) { bestDist = dist; best = z; }
        }
        return best;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double readSliceThicknessDicom(SeriesData sd) {
        try {
            File f = sd.getFiles().get(0);
            String meta = sd.getMetadata(f);
            String s = DicomProcessor.getTag(meta, "0018,0050");
            if (s != null) return Double.parseDouble(s.trim());
        } catch (Exception ignore) {}
        return Double.NaN;
    }

    // ------------------------------------------------------------------
    // 1. Exatidao do numero de CT
    // ------------------------------------------------------------------

    /** Inserto detectado: posicao, HU medio e desvio padrao (ROI reduzido). */
    private static class Insert {
        double cx, cy, hu, sd;
        Insert(double cx, double cy, double hu, double sd) { this.cx = cx; this.cy = cy; this.hu = hu; this.sd = sd; }
    }

    /**
     * Mede os numeros de CT dos materiais do Modulo 1. Para evitar o lado da
     * secao que contem o parafuso/BB (gerador de artefatos), a medida e feita
     * nas duas fatias EXTREMAS da secao do Modulo 1 e escolhe-se a que
     * apresentar o melhor resultado (4 insertos bem definidos e maior separacao
     * de contraste Osso-Ar).
     */
    private static void measureCtNumbers(LoadedSeries ls, AcrQcResult r, List<Integer> mod1) {
        int first = mod1.get(0);
        int last = mod1.get(mod1.size() - 1);

        // Candidatas: as duas fatias extremas (uma delas tende a ter o parafuso).
        int[] candidates = (first == last) ? new int[]{first} : new int[]{first, last};

        CtCandidate best = null;
        for (int z : candidates) {
            CtCandidate c = evaluateCtSlice(ls, z);
            if (c == null) continue;
            if (best == null || c.score() > best.score()) best = c;
        }

        if (best == null) {
            // Fallback: usa a fatia central do Modulo 1.
            best = evaluateCtSlice(ls, r.mod1Center);
        }
        if (best == null) {
            r.message = "Falha ao detectar insertos para exatidao do numero de CT.";
            return;
        }

        r.ctSlice = best.slice;

        // Classifica os 4 insertos por HU: Ar < Polietileno < Acrilico < Osso.
        List<Insert> ins = new ArrayList<>(best.inserts);
        ins.sort((a, b) -> Double.compare(a.hu, b.hu));
        if (ins.size() >= 4) {
            Insert air = ins.get(0), poly = ins.get(1), acr = ins.get(2), bone = ins.get(3);
            r.airIn   = new Measure("Ar (interno)",   air.hu,  air.sd);
            r.poly    = new Measure("Polietileno",    poly.hu, poly.sd);
            r.acrylic = new Measure("Acrilico",       acr.hu,  acr.sd);
            r.bone    = new Measure("Osso",           bone.hu, bone.sd);
        }

        // Agua: medida no centro do phantom nesta fatia (inserto de agua/fundo central).
        ImageProcessor ip = ls.stack.getProcessor(best.slice);
        double[] center = phantomCenter(ip, ls.cal);
        double rCt = CT_ROI_RADIUS_MM / ls.px;
        r.water = measureOval(ip, ls.cal, center[0], center[1], rCt, "Agua");

        // Ar no fundo/externo: ROI fora do phantom (canto superior esquerdo).
        r.airOut = measureAirOutside(ip, ls.cal, ls.px);
    }

    private static class CtCandidate {
        int slice;
        List<Insert> inserts;
        double contrastSpan; // |HU_max - HU_min| entre os insertos
        double score() {
            // Prioriza ter 4 insertos e grande separacao Osso-Ar (fatia "limpa").
            double base = (inserts != null ? inserts.size() : 0) * 1e6;
            return base + contrastSpan;
        }
    }

    /** Detecta insertos numa fatia e mede HU com ROI circular menor que o inserto. */
    private static CtCandidate evaluateCtSlice(LoadedSeries ls, int slice) {
        if (slice < 1 || slice > ls.stack.getSize()) return null;
        ImageStack single = new ImageStack(ls.stack.getWidth(), ls.stack.getHeight());
        single.addSlice(ls.stack.getProcessor(slice));

        Segmenter.DetectorResult det;
        try {
            det = Segmenter.edgeFinder(single, ls.cal, null, true, true);
        } catch (Exception e) {
            return null;
        }
        if (det == null || det.roi == null || det.roi.isEmpty()) return null;

        ImageProcessor ip = ls.stack.getProcessor(slice);
        List<Insert> inserts = new ArrayList<>();
        double minHU = Double.MAX_VALUE, maxHU = -Double.MAX_VALUE;

        for (int i = 0; i < det.roi.size(); i++) {
            Roi edgeRoi = det.roi.get(i);
            if (edgeRoi == null) continue;
            java.awt.Rectangle b = edgeRoi.getBounds();
            double cx = b.x + b.width / 2.0;
            double cy = b.y + b.height / 2.0;

            // Raio efetivo do inserto detectado (em px) e ROI de medida MENOR que
            // o inserto para nao encostar nas bordas (70% do raio detectado,
            // limitado a no maximo CT_ROI_RADIUS_MM).
            double detRadiusPx = Math.min(b.width, b.height) / 2.0;
            double measRadiusPx = Math.min(detRadiusPx * 0.70, CT_ROI_RADIUS_MM / ls.px);
            if (measRadiusPx < 2) measRadiusPx = Math.max(2, detRadiusPx * 0.6);

            Measure m = measureOval(ip, ls.cal, cx, cy, measRadiusPx, "insert");
            inserts.add(new Insert(cx, cy, m.mean, m.sd));
            minHU = Math.min(minHU, m.mean);
            maxHU = Math.max(maxHU, m.mean);
        }

        if (inserts.isEmpty()) return null;

        CtCandidate c = new CtCandidate();
        c.slice = slice;
        c.inserts = inserts;
        c.contrastSpan = (maxHU - minHU);
        return c;
    }

    /**
     * Mede o ar no fundo (fora do phantom). Muitas reconstrucoes preenchem a
     * regiao fora do FOV circular com um valor de "padding" (ex.: -3024 HU), que
     * NAO e ar real. Por isso medimos a estatistica robusta do ar real ignorando
     * esses pixels de padding (abaixo de -1050 HU) e qualquer pixel acima de
     * -800 HU (que ja seria estrutura/phantom). Se nao houver ar real suficiente
     * (FOV justo), retorna invalido.
     */
    private static Measure measureAirOutside(ImageProcessor ip, Calibration cal, double px) {
        int w = ip.getWidth(), h = ip.getHeight();
        // Varre a faixa superior da imagem (fora do phantom), coletando ar real.
        double sum = 0, sumSq = 0; long n = 0;
        int bandH = (int) Math.min(h / 4, Math.round(40.0 / px));
        for (int y = 0; y < bandH; y++) {
            for (int x = 0; x < w; x++) {
                double hu = (cal != null) ? cal.getCValue(ip.getf(x, y)) : ip.getf(x, y);
                // Ar real do ambiente: entre -1050 e -800 HU (descarta padding e phantom).
                if (hu > -1050 && hu < -800) { sum += hu; sumSq += hu * hu; n++; }
            }
        }
        if (n < 50) {
            Measure m = new Measure("Ar (fundo/externo)");
            m.valid = false;
            return m;
        }
        double mean = sum / n;
        double var = Math.max(0, sumSq / n - mean * mean);
        return new Measure("Ar (fundo/externo)", mean, Math.sqrt(var));
    }

    // ------------------------------------------------------------------
    // 2. Uniformidade + ruido (Modulo 3)
    // ------------------------------------------------------------------

    private static void measureUniformity(LoadedSeries ls, AcrQcResult r, int slice) {
        if (slice < 1 || slice > ls.stack.getSize()) return;
        ImageProcessor ip = ls.stack.getProcessor(slice);
        double[] c = phantomCenter(ip, ls.cal);

        double rUnif = UNIF_ROI_RADIUS_MM / ls.px;
        double dist = UNIF_PERIPHERAL_MM / ls.px;

        r.uCenter = measureOval(ip, ls.cal, c[0], c[1], rUnif, "Centro");
        r.uTop    = measureOval(ip, ls.cal, c[0], c[1] - dist, rUnif, "Topo (12h)");
        r.uBottom = measureOval(ip, ls.cal, c[0], c[1] + dist, rUnif, "Fundo (6h)");
        r.uLeft   = measureOval(ip, ls.cal, c[0] - dist, c[1], rUnif, "Esquerda (9h)");
        r.uRight  = measureOval(ip, ls.cal, c[0] + dist, c[1], rUnif, "Direita (3h)");

        r.noise = r.uCenter.sd;

        double maxDiff = 0;
        for (Measure m : new Measure[]{r.uTop, r.uBottom, r.uLeft, r.uRight}) {
            if (m.valid && r.uCenter.valid) {
                maxDiff = Math.max(maxDiff, Math.abs(m.mean - r.uCenter.mean));
            }
        }
        r.maxUniformityDiff = maxDiff;
    }

    // ------------------------------------------------------------------
    // 3. Baixo contraste / CNR (Modulo 2)
    // ------------------------------------------------------------------

    private static void measureLowContrast(LoadedSeries ls, AcrQcResult r, int slice) {
        if (slice < 1 || slice > ls.stack.getSize()) return;
        ImageProcessor ip = ls.stack.getProcessor(slice);
        double[] c = phantomCenter(ip, ls.cal);

        double rCt = CT_ROI_RADIUS_MM / ls.px;
        // A maior estrutura de baixo contraste do Modulo 2 do ACR fica proxima
        // do centro; medimos o fundo no centro e o sinal ~37-58mm acima.
        double signalDist = 58.0 / ls.px;

        r.lcBackground = measureOval(ip, ls.cal, c[0], c[1], rCt, "Fundo");
        r.lcSignal     = measureOval(ip, ls.cal, c[0], c[1] - signalDist, rCt, "Massa (sinal)");

        if (r.lcBackground.valid && r.lcSignal.valid && r.lcBackground.sd > 0) {
            r.cnr = Math.abs(r.lcSignal.mean - r.lcBackground.mean) / r.lcBackground.sd;
        }
    }

    // ------------------------------------------------------------------
    // 4. Espessura de corte (rampas do Modulo 1)
    // ------------------------------------------------------------------

    /**
     * Mede a espessura de corte pelas rampas do Modulo 1. As rampas do ACR estao
     * inclinadas de forma que o numero de "pics" (segmentos acima da meia-altura)
     * ao longo de um perfil vertical corresponde a espessura em mm (cada
     * elemento da rampa = 0,5mm, 2 por mm -> a contagem de picos na pratica da a
     * espessura em mm com o espacamento adotado na macro de referencia).
     *
     * Reproduz a logica de contagem de picos da macro do usuario: threshold =
     * max/2, distancia minima entre picos de 3mm.
     */
    private static void measureSliceThickness(LoadedSeries ls, AcrQcResult r) {
        if (r.mod1Center < 1 || r.mod1Center > ls.stack.getSize()) return;
        ImageProcessor ip = ls.stack.getProcessor(r.mod1Center);
        double[] c = phantomCenter(ip, ls.cal);

        // Dois pares de rampas (superior e inferior) dispostos horizontalmente,
        // proximos do centro. Perfis verticais de ~73mm de altura, 2 colunas por
        // lado, com pequeno offset horizontal (parametros da macro de referencia).
        double rampLenMm = 73.0;
        double rampHalfMm = rampLenMm / 2.0;
        int hPx = (int) Math.round(rampLenMm / ls.py);
        double colOffsetMm = 5.3; // distancia entre as duas colunas de cada par

        // Rampas superiores ~ -14mm em Y do centro; inferiores ~ +14mm.
        double topYStartMm = -rampHalfMm - 14.0;
        double botYStartMm = 14.0;
        double xBase = c[0]; // centralizadas em X

        double[] profTop1 = verticalProfile(ip, ls.cal, xBase - colOffsetMm / ls.px, c[1] + topYStartMm / ls.py, hPx);
        double[] profTop2 = verticalProfile(ip, ls.cal, xBase + colOffsetMm / ls.px, c[1] + topYStartMm / ls.py, hPx);
        double[] profBot1 = verticalProfile(ip, ls.cal, xBase - colOffsetMm / ls.px, c[1] + botYStartMm / ls.py, hPx);
        double[] profBot2 = verticalProfile(ip, ls.cal, xBase + colOffsetMm / ls.px, c[1] + botYStartMm / ls.py, hPx);

        double maxTop = Math.max(arrMax(profTop1), arrMax(profTop2));
        int pTop1 = countPeaks(profTop1, maxTop, ls.py);
        int pTop2 = countPeaks(profTop2, maxTop, ls.py);

        double maxBot = Math.max(arrMax(profBot1), arrMax(profBot2));
        int pBot1 = countPeaks(profBot1, maxBot, ls.py);
        int pBot2 = countPeaks(profBot2, maxBot, ls.py);

        r.sliceThicknessTop = (pTop1 + pTop2) / 2.0;
        r.sliceThicknessBottom = (pBot1 + pBot2) / 2.0;

        // Aviso de confiabilidade: se os dois lados divergirem muito, a
        // localizacao das rampas pode estar imprecisa (geometria a calibrar).
        if (!Double.isNaN(r.sliceThicknessTop) && !Double.isNaN(r.sliceThicknessBottom)) {
            double diff = Math.abs(r.sliceThicknessTop - r.sliceThicknessBottom);
            double mean = (r.sliceThicknessTop + r.sliceThicknessBottom) / 2.0;
            if (mean > 0 && diff > 0.4 * mean) {
                r.sliceThicknessNote = "lados divergentes (verificar posicao das rampas)";
            }
        }
    }

    private static double[] verticalProfile(ImageProcessor ip, Calibration cal, double x, double yStart, int nPx) {
        double[] prof = new double[Math.max(1, nPx)];
        int xi = (int) Math.round(x);
        for (int k = 0; k < prof.length; k++) {
            int yi = (int) Math.round(yStart) + k;
            if (xi < 0 || xi >= ip.getWidth() || yi < 0 || yi >= ip.getHeight()) {
                prof[k] = -1000;
            } else {
                prof[k] = (cal != null) ? cal.getCValue(ip.getf(xi, yi)) : ip.getf(xi, yi);
            }
        }
        return prof;
    }

    private static double arrMax(double[] a) {
        double m = -Double.MAX_VALUE;
        for (double v : a) if (v > m) m = v;
        return m;
    }

    /** Conta picos acima da meia-altura, exigindo separacao minima de 3mm. */
    private static int countPeaks(double[] profile, double maxV, double pixelSizeMM) {
        double threshold = maxV / 2.0;
        int peakCount = 0;
        boolean inPeak = false;
        int peakStart = 0;
        double lastPeakPosMM = -9999;
        double minDistanceMM = 3.0;

        for (int i = 0; i < profile.length; i++) {
            if (profile[i] > threshold) {
                if (!inPeak) { inPeak = true; peakStart = i; }
            } else if (inPeak) {
                int peakEnd = i - 1;
                double centerPix = (peakStart + peakEnd) / 2.0;
                double posMM = centerPix * pixelSizeMM;
                if ((posMM - lastPeakPosMM) >= minDistanceMM) { peakCount++; lastPeakPosMM = posMM; }
                inPeak = false;
            }
        }
        if (inPeak) {
            int peakEnd = profile.length - 1;
            double centerPix = (peakStart + peakEnd) / 2.0;
            double posMM = centerPix * pixelSizeMM;
            if ((posMM - lastPeakPosMM) >= minDistanceMM) peakCount++;
        }
        return peakCount;
    }

    // ------------------------------------------------------------------
    // 5. Exatidao de distancia entre os pinos de referencia (Modulo 3)
    // ------------------------------------------------------------------

    /** Marcador (bead/pino) detectado: centroide e angulo em relacao ao centro. */
    private static class Marker {
        double x, y, hu, radiusMm, angleDeg;
    }

    /**
     * Mede a distancia entre os dois pinos de referencia no modulo de
     * uniformidade/beads, com VERIFICACAO DE ORIENTACAO (robusto a phantom
     * invertido).
     *
     * Em vez de assumir que os pinos estao na horizontal, detecta TODOS os
     * marcadores de alto HU dentro do phantom, agrupa em clusters e procura o
     * par de marcadores diametralmente opostos (aprox. simetricos em relacao ao
     * centro) cuja distancia seja mais proxima do valor nominal (100 mm). A
     * orientacao do par (horizontal ou vertical) e reportada: se o par estiver
     * na vertical quando se esperava horizontal (ou vice-versa), sinaliza que o
     * phantom pode estar invertido/rotacionado, permitindo conferencia pelo
     * usuario.
     */
    private static void measurePinDistance(LoadedSeries ls, AcrQcResult r, int slice) {
        if (slice < 1 || slice > ls.stack.getSize()) { r.pinNote = "fatia de pinos invalida"; return; }
        ImageProcessor ip = ls.stack.getProcessor(slice);
        double[] c = phantomCenter(ip, ls.cal);

        List<Marker> markers = detectMarkers(ip, ls.cal, ls.px, ls.py, c, 300.0);
        if (markers.size() < 2) {
            markers = detectMarkers(ip, ls.cal, ls.px, ls.py, c, 150.0);
        }
        r.pinMarkerCount = markers.size();
        if (markers.size() < 2) {
            r.pinDistance = Double.NaN;
            r.pinNote = "menos de 2 marcadores detectados (nenhum pino de referencia)";
            return;
        }

        // Para cada par de marcadores DIAMETRALMENTE OPOSTOS (ponto medio proximo
        // do centro), classifica como horizontal ou vertical e guarda a melhor
        // distancia de cada orientacao. Isto torna a medida robusta a inversao/
        // rotacao do phantom: reportamos AMBAS as orientacoes e sinalizamos quando
        // o par alinhado nao esta na horizontal esperada.
        double bestHdist = Double.NaN, bestVdist = Double.NaN;
        double bestHoff = Double.MAX_VALUE, bestVoff = Double.MAX_VALUE;
        for (int i = 0; i < markers.size(); i++) {
            for (int j = i + 1; j < markers.size(); j++) {
                Marker a = markers.get(i), b = markers.get(j);
                double mx = (a.x + b.x) / 2.0, my = (a.y + b.y) / 2.0;
                double offCenterMm = Math.hypot(mx - c[0], my - c[1]) * ls.px;
                if (offCenterMm > 10.0) continue; // nao e um par oposto
                double dxMm = (b.x - a.x) * ls.px, dyMm = (b.y - a.y) * ls.py;
                double distMm = Math.hypot(dxMm, dyMm);
                double absAngle = Math.toDegrees(Math.atan2(Math.abs(dyMm), Math.abs(dxMm)));
                if (absAngle < 45.0) { // horizontal
                    if (offCenterMm < bestHoff) { bestHoff = offCenterMm; bestHdist = distMm; }
                } else { // vertical
                    if (offCenterMm < bestVoff) { bestVoff = offCenterMm; bestVdist = distMm; }
                }
            }
        }

        r.pinDistanceH = bestHdist;
        r.pinDistanceV = bestVdist;

        // Escolhe o par cuja distancia e mais proxima do nominal como principal.
        boolean hasH = !Double.isNaN(bestHdist), hasV = !Double.isNaN(bestVdist);
        if (hasH && hasV) {
            double eH = Math.abs(bestHdist - AcrQcResult.PIN_DISTANCE_NOMINAL_MM);
            double eV = Math.abs(bestVdist - AcrQcResult.PIN_DISTANCE_NOMINAL_MM);
            if (eH <= eV) { r.pinDistance = bestHdist; r.pinOrientation = "horizontal"; }
            else          { r.pinDistance = bestVdist; r.pinOrientation = "vertical"; }
        } else if (hasH) {
            r.pinDistance = bestHdist; r.pinOrientation = "horizontal";
        } else if (hasV) {
            r.pinDistance = bestVdist; r.pinOrientation = "vertical";
        } else {
            r.pinDistance = Double.NaN;
            r.pinNote = markers.size() + " marcadores, mas nenhum par diametralmente oposto";
            return;
        }

        // Conferencia de inversao: no ACR padrao (nao rotacionado) o par de
        // referencia de distancia esta alinhado HORIZONTALMENTE. Se o par
        // utilizado estiver na vertical, o phantom pode estar invertido/
        // rotacionado -> sinaliza para conferencia do usuario.
        r.pinPhantomInverted = r.pinOrientation.equals("vertical");
        r.pinNote = String.format(java.util.Locale.US,
                "%d marcadores | H=%s mm | V=%s mm | par usado: %s%s",
                markers.size(),
                hasH ? String.format(java.util.Locale.US, "%.1f", bestHdist) : "-",
                hasV ? String.format(java.util.Locale.US, "%.1f", bestVdist) : "-",
                r.pinOrientation,
                r.pinPhantomInverted ? " (VERIFICAR: phantom possivelmente invertido/rotacionado)" : "");
    }

    /** Detecta marcadores de alto HU (beads/pinos) dentro do phantom e agrupa em clusters. */
    private static List<Marker> detectMarkers(ImageProcessor ip, Calibration cal,
                                              double px, double py, double[] c, double thrHU) {
        int w = ip.getWidth(), h = ip.getHeight();
        double rInner = 30.0 / px;   // ignora o centro (evita falsos positivos de texturas)
        double rOuter = 90.0 / px;   // dentro do corpo do phantom

        // Coleta pixels acima do limiar no anel.
        List<double[]> pts = new ArrayList<>();
        int x0 = (int) Math.max(0, c[0] - rOuter), x1 = (int) Math.min(w - 1, c[0] + rOuter);
        int y0 = (int) Math.max(0, c[1] - rOuter), y1 = (int) Math.min(h - 1, c[1] + rOuter);
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                double d = Math.hypot(x - c[0], y - c[1]);
                if (d < rInner || d > rOuter) continue;
                double hu = (cal != null) ? cal.getCValue(ip.getf(x, y)) : ip.getf(x, y);
                if (hu > thrHU) pts.add(new double[]{x, y, hu});
            }
        }

        // Agrupamento por proximidade.
        double clusterPx = 10.0 / px;
        List<Marker> markers = new ArrayList<>();
        boolean[] used = new boolean[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            if (used[i]) continue;
            double ax = pts.get(i)[0], ay = pts.get(i)[1];
            double gx = 0, gy = 0, sumh = 0; int n = 0;
            for (int j = 0; j < pts.size(); j++) {
                if (used[j]) continue;
                if (Math.hypot(pts.get(j)[0] - ax, pts.get(j)[1] - ay) <= clusterPx * 2) {
                    used[j] = true; gx += pts.get(j)[0]; gy += pts.get(j)[1]; sumh += pts.get(j)[2]; n++;
                }
            }
            if (n < 3) continue; // ignora clusters minusculos (ruido)
            Marker m = new Marker();
            m.x = gx / n; m.y = gy / n; m.hu = sumh / n;
            m.radiusMm = Math.hypot(m.x - c[0], m.y - c[1]) * px;
            m.angleDeg = Math.toDegrees(Math.atan2(m.y - c[1], m.x - c[0]));
            markers.add(m);
        }
        return markers;
    }

    // ------------------------------------------------------------------
    // Linha unica para o Excel (TAB-separada, decimal com virgula)
    // ------------------------------------------------------------------

    private static String fmt(double v) {
        if (Double.isNaN(v)) return "N/A";
        return String.format(java.util.Locale.US, "%.2f", v).replace('.', ',');
    }

    // ------------------------------------------------------------------
    // Pipeline de qualidade de imagem: TTF, NPS e NPWE (d')
    //
    // Estes resultados sao calculados automaticamente pela aba CQ ACR, mas
    // destinam-se a ser PUBLICADOS nos paineis TTF/NPS/NPWE respectivos.
    // ------------------------------------------------------------------

    /** Resultado do pipeline de qualidade de imagem para publicacao nos paineis. */
    public static class ImageQualityResult {
        public boolean ok = false;
        public String message = "";

        // TTF (do insert de menor contraste, no Modulo 1 sem bordas)
        public TtfCalc.mtfResult ttf;        // mapa completo (todos os inserts detectados)
        public int lowestContrastInsertId = -1;
        public double[] ttfFreqSel;          // TTF do insert de menor contraste
        public double[] ttfDataSel;

        // NPS (todas as fatias uniformes, ROIs 80mm, raio 80mm)
        public double[] npsFreq;
        public double[] npsData;
        public double[][] nps2D;
        public double npsVariance = Double.NaN;
        public double npsIntegrated = Double.NaN;
        public double npsPeakFreq = Double.NaN;
        public double npsMeanFreq = Double.NaN;
        public int npsSlicesUsed = 0;

        // d' / AUC (tarefa 6mm diametro -> raio 3mm, 6 HU)
        public double dPrime = Double.NaN;
        public double auc = Double.NaN;
        public double taskRadiusMm = 3.0;     // 6mm de diametro
        public double taskContrastHU = 6.0;
        public Calibration cal;
    }

    // NPS: ROIs quadradas distribuidas num anel dentro do modulo uniforme.
    // Observacao: ROIs de 80mm num phantom ACR (raio ~100mm) saem do corpo e
    // capturam o padding fora do FOV, inflando a variancia. Usamos ROI de 30mm
    // num anel de 60mm, que mantem todos os ROIs dentro da regiao de agua
    // (validado na serie CRANIO S_C: SD ~5-6 HU por ROI). Ajustaveis caso o
    // phantom/FOV comporte ROIs maiores.
    private static final double NPS_ROI_SIZE_MM = 30.0;      // lado do ROI quadrado
    private static final double NPS_DIST_RADIUS_MM = 60.0;   // raio de distribuicao dos ROIs
    private static final int    NPS_NUM_ROIS = 12;
    private static final double TASK_DIAMETER_MM = 6.0;
    private static final double TASK_CONTRAST_HU = 6.0;

    /**
     * Executa o pipeline de qualidade de imagem (TTF, NPS, d') seguindo os
     * requisitos de CQ:
     *   - NPS em TODAS as fatias uniformes do phantom, ROIs quadradas de 80mm e
     *     raio de distribuicao de 80mm.
     *   - TTF nas fatias do Modulo 1, ignorando a primeira e a ultima (bordas).
     *   - NPWE (d') para o insert de MENOR contraste, tarefa de 6mm de diametro
     *     (raio 3mm) e 6 HU, usando os parametros de visualizacao fornecidos.
     *
     * @param eyeFilter {viewDistance, displaySize, fov} pre-configurados no painel NPWE.
     */
    public static ImageQualityResult runImageQualityPipeline(SeriesData sd, double[] eyeFilter) {
        ImageQualityResult q = new ImageQualityResult();
        if (sd == null || sd.getFiles().isEmpty()) { q.message = "Nenhuma serie ativa."; return q; }

        LoadedSeries ls = loadSeries(sd);
        if (ls.stack.getSize() == 0) { q.message = "Nao foi possivel carregar a serie."; return q; }
        q.cal = ls.cal;

        int[] limits = findPhantomLimits(ls.stack, ls.cal, ls.px, ls.py);
        if (limits[0] == -1) { q.message = "Phantom nao localizado."; return q; }

        // Mapeia os modulos por conteudo (robusto a orientacao/inversao).
        ModuleMap mm = mapModules(ls, limits[0], limits[1]);
        if (mm.insertSlices.isEmpty()) { q.message = "Modulo 1 nao encontrado."; return q; }

        // --- TTF: fatias do Modulo 1 (maior segmento contiguo de insertos),
        //     ignorando a primeira e a ultima fatia (bordas do modulo) ---
        List<Integer> mod1Sorted = new ArrayList<>(mm.insertSlices);
        Collections.sort(mod1Sorted);
        List<int[]> insSegs = contiguousSegments(mod1Sorted);
        int[] insBest = insSegs.get(0);
        for (int[] s : insSegs) if ((s[1] - s[0]) > (insBest[1] - insBest[0])) insBest = s;
        List<Integer> ttfSlices = new ArrayList<>();
        for (int z = insBest[0]; z <= insBest[1]; z++) ttfSlices.add(z);
        if (ttfSlices.size() > 2) ttfSlices = ttfSlices.subList(1, ttfSlices.size() - 1);
        computeTtf(ls, ttfSlices, q);

        // --- NPS: TODAS as fatias uniformes limpas (classe UNIFORM); se nao
        //     houver, usa o modulo de beads filtrando as fatias com bead ---
        List<Integer> uniformSlices = new ArrayList<>(mm.uniformSlices);
        Collections.sort(uniformSlices);
        if (uniformSlices.isEmpty() && !mm.beadsSlices.isEmpty()) {
            int bs = Collections.min(mm.beadsSlices), be = Collections.max(mm.beadsSlices);
            uniformSlices = filterUniformitySlices(ls.stack, bs, be, ls.cal, ls.px, ls.py);
        }
        computeNps(ls, uniformSlices, q);

        // --- d' / NPWE ---
        if (q.ttfDataSel != null && q.npsData != null) {
            q.taskRadiusMm = TASK_DIAMETER_MM / 2.0;
            q.taskContrastHU = TASK_CONTRAST_HU;
            double vDist = eyeFilter != null && eyeFilter.length > 0 ? eyeFilter[0] : 500.0;
            double dSize = eyeFilter != null && eyeFilter.length > 1 ? eyeFilter[1] : 305.0;
            double fov   = eyeFilter != null && eyeFilter.length > 2 ? eyeFilter[2] : 250.0;
            DetectabilityIndexCalculator calc = new DetectabilityIndexCalculator(
                    q.ttfDataSel, q.ttfFreqSel, q.npsData, q.npsFreq,
                    q.taskRadiusMm, q.taskContrastHU, vDist, dSize, fov);
            q.dPrime = calc.compute();
            q.auc = 0.5 + 0.5 * org.apache.commons.math3.special.Erf.erf(q.dPrime / 2.0);
        }

        q.ok = true;
        return q;
    }

    /** Filtra fatias de uniformidade removendo as que contem beads (max HU alto). */
    static List<Integer> filterUniformitySlices(ImageStack stack, int start, int end,
                                                 Calibration cal, double px, double py) {
        List<Integer> valid = new ArrayList<>();
        int w = stack.getWidth(), h = stack.getHeight();
        double rx = 80.0 / px, ry = 80.0 / py;
        Roi waterRoi = new OvalRoi((w / 2.0) - rx, (h / 2.0) - ry, rx * 2, ry * 2);
        for (int i = start; i <= end; i++) {
            ImageProcessor ip = stack.getProcessor(i);
            ip.setRoi(waterRoi);
            ImageStatistics stats = ImageStatistics.getStatistics(ip);
            double maxHU = (cal != null) ? cal.getCValue(stats.max) : stats.max;
            ip.resetRoi();
            if (maxHU <= 90) valid.add(i);
        }
        return valid;
    }

    private static void computeTtf(LoadedSeries ls, List<Integer> slices, ImageQualityResult q) {
        if (slices == null || slices.isEmpty()) return;
        ImageStack sub = new ImageStack(ls.stack.getWidth(), ls.stack.getHeight());
        for (int z : slices) sub.addSlice(ls.stack.getProcessor(z));

        try {
            Segmenter.DetectorResult det = Segmenter.edgeFinder(sub, ls.cal, null, true, true);
            if (det == null || det.rois == null || det.rois.isEmpty()) return;
            TtfCalc.mtfResult mtf = TtfCalc.analisarStackComInserts(sub, ls.cal, det, 128);
            if (mtf == null || mtf.ttfs.isEmpty()) return;
            q.ttf = mtf;

            // Insert de MENOR contraste (|I1 - I0| minimo, ou menor contraste registrado).
            int bestId = -1;
            double minContrast = Double.MAX_VALUE;
            for (Integer id : mtf.ttfs.keySet()) {
                Double c = mtf.contrasts.get(id);
                double contrast;
                if (c != null) {
                    contrast = Math.abs(c);
                } else {
                    Double i0 = mtf.I0.get(id), i1 = mtf.I1.get(id);
                    contrast = (i0 != null && i1 != null) ? Math.abs(i1 - i0) : Double.MAX_VALUE;
                }
                if (contrast < minContrast) { minContrast = contrast; bestId = id; }
            }
            if (bestId != -1) {
                q.lowestContrastInsertId = bestId;
                q.ttfDataSel = mtf.ttfs.get(bestId);
                q.ttfFreqSel = mtf.freqs.get(bestId);
            }
        } catch (Exception e) {
            q.message = "Falha no calculo da TTF: " + e.getMessage();
        }
    }

    private static void computeNps(LoadedSeries ls, List<Integer> slices, ImageQualityResult q) {
        if (slices == null || slices.isEmpty()) { q.message = "Nenhuma fatia uniforme para NPS."; return; }

        int roiSizePx = (int) Math.round(NPS_ROI_SIZE_MM / ls.px);
        if (roiSizePx % 2 != 0) roiSizePx++; // par facilita FFT/centro
        double distRadiusPx = NPS_DIST_RADIUS_MM / ls.px;

        double[][] sumNPS2D = new double[roiSizePx][roiSizePx];
        int validRecords = 0;
        double sumVariance = 0.0;

        for (int z : slices) {
            ImageProcessor ip = ls.stack.getProcessor(z);
            PolygonRoi phantom = Segmenter.segmenter(ip, ls.cal, -200, 5000, 0.2, false, 0, 20);
            if (phantom == null) continue;
            List<Roi> records = Segmenter.createCircularRecordRois(
                    ip, ls.cal, phantom, NPS_NUM_ROIS, roiSizePx, distRadiusPx, false, z);

            for (Roi rec : records) {
                ip.setRoi(rec);
                ImageProcessor crop = ip.crop();
                ip.resetRoi();
                if (crop.getWidth() != roiSizePx || crop.getHeight() != roiSizePx) continue;

                ImageStatistics st = ImageStatistics.getStatistics(crop);
                int N = crop.getWidth() * crop.getHeight();
                sumVariance += Math.pow(st.stdDev, 2) * (double) (N - 1) / N;

                float[][] roiData = crop.getFloatArray();
                double[][] detr = Detrending.detrendWith2ndOrderPolynomial(roiData);
                float[][] detrF = new float[roiSizePx][roiSizePx];
                for (int x = 0; x < roiSizePx; x++)
                    for (int y = 0; y < roiSizePx; y++)
                        detrF[x][y] = (float) detr[x][y];
                ij.process.FloatProcessor fp = new ij.process.FloatProcessor(detrF);

                double[][] ps = NPScalc.calculate2DNPS(fp, ls.cal, roiSizePx);
                for (int y = 0; y < roiSizePx; y++)
                    for (int x = 0; x < roiSizePx; x++)
                        sumNPS2D[y][x] += ps[y][x];
                validRecords++;
            }
        }

        if (validRecords == 0) { q.message = "Nenhum ROI valido para NPS."; return; }
        q.npsSlicesUsed = slices.size();
        q.npsVariance = sumVariance / validRecords;

        for (int y = 0; y < roiSizePx; y++)
            for (int x = 0; x < roiSizePx; x++)
                sumNPS2D[y][x] /= validRecords;

        double[] radialFreq = new double[roiSizePx / 2];
        double[] radialNPS = NPScalc.radialProfile(sumNPS2D, ls.cal, radialFreq);

        // Estima o valor em frequencia zero via ajuste quadratico dos primeiros pontos.
        try {
            org.apache.commons.math3.fitting.WeightedObservedPoints obs =
                    new org.apache.commons.math3.fitting.WeightedObservedPoints();
            for (int i = 1; i <= 5 && i < radialFreq.length; i++) obs.add(radialFreq[i], radialNPS[i]);
            double[] coeff = org.apache.commons.math3.fitting.PolynomialCurveFitter.create(2).fit(obs.toList());
            radialNPS[0] = Math.max(0, coeff[0]);
            if (sumNPS2D.length > roiSizePx / 2) sumNPS2D[roiSizePx / 2][roiSizePx / 2] = radialNPS[0];
        } catch (Exception ignore) {}

        q.nps2D = sumNPS2D;
        q.npsFreq = radialFreq;
        q.npsData = radialNPS;

        // Metricas para exibicao no painel NPS.
        double integ = 0;
        for (int y = 0; y < roiSizePx; y++)
            for (int x = 0; x < roiSizePx; x++)
                integ += sumNPS2D[y][x];
        integ *= (1.0 / (roiSizePx * ls.px)) * (1.0 / (roiSizePx * ls.py));
        q.npsIntegrated = integ;

        double sumF = 0, sum1 = 0;
        for (int i = 0; i < radialFreq.length; i++) { sumF += radialFreq[i] * radialNPS[i]; sum1 += radialNPS[i]; }
        q.npsMeanFreq = (sum1 > 0) ? sumF / sum1 : 0.0;
        q.npsPeakFreq = NPScalc.findPeakFreq(radialFreq, radialNPS, 4);
    }

    private static String buildExcelLine(AcrQcResult r) {
        StringBuilder sb = new StringBuilder();
        String T = "\t";
        sb.append(fmt(r.sliceThicknessTop)).append(T)
          .append(fmt(r.sliceThicknessBottom)).append(T)
          .append(fmt(r.airIn.mean)).append(T).append(fmt(r.airIn.sd)).append(T)
          .append(fmt(r.poly.mean)).append(T).append(fmt(r.poly.sd)).append(T)
          .append(fmt(r.water.mean)).append(T).append(fmt(r.water.sd)).append(T)
          .append(fmt(r.acrylic.mean)).append(T).append(fmt(r.acrylic.sd)).append(T)
          .append(fmt(r.bone.mean)).append(T).append(fmt(r.bone.sd)).append(T)
          .append(fmt(r.airOut.mean)).append(T).append(fmt(r.airOut.sd)).append(T)
          .append(fmt(r.lcSignal.mean)).append(T)
          .append(fmt(r.lcBackground.mean)).append(T).append(fmt(r.lcBackground.sd)).append(T)
          .append(fmt(r.cnr)).append(T)
          .append(fmt(r.uTop.mean)).append(T).append(fmt(r.uTop.sd)).append(T)
          .append(fmt(r.uBottom.mean)).append(T).append(fmt(r.uBottom.sd)).append(T)
          .append(fmt(r.uLeft.mean)).append(T).append(fmt(r.uLeft.sd)).append(T)
          .append(fmt(r.uRight.mean)).append(T).append(fmt(r.uRight.sd)).append(T)
          .append(fmt(r.uCenter.mean)).append(T).append(fmt(r.uCenter.sd)).append(T)
          .append(fmt(r.noise)).append(T)
          .append(fmt(r.pinDistance));
        return sb.toString();
    }
}
