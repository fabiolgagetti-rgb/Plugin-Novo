package br.rededor.fisicamedica;

import java.awt.Rectangle;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Line;
import ij.gui.OvalRoi;
import ij.gui.PointRoi;
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

    /**
     * ROI efetivamente usada por um teste. O analisador apenas coleta as ROIs;
     * a interface as publica no ROI Manager, na EDT, depois que o processamento
     * termina e a identidade da serie ativa e confirmada.
     */
    public static class RoiRecord {
        public final Roi roi;
        public final String name;
        public final int slice;
        public final String test;

        public RoiRecord(Roi roi, String name, int slice, String test) {
            this.roi = roi == null ? null : (Roi) roi.clone();
            this.name = name;
            this.slice = slice;
            this.test = test;
            if (this.roi != null) {
                this.roi.setName(name);
                this.roi.setPosition(slice);
            }
        }
    }

    private static void addRoiRecord(List<RoiRecord> records, Roi roi,
                                     String name, int slice, String test) {
        if (records != null && roi != null) records.add(new RoiRecord(roi, name, slice, test));
    }

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

        // ROIs dos testes convencionais, prontas para publicacao no ROI Manager.
        public final List<RoiRecord> rois = new ArrayList<>();

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

    // ------------------------------------------------------------------
    // Carregamento da serie em stack (com calibracao)
    // ------------------------------------------------------------------

    static class LoadedSeries {
        ImageStack stack;
        Calibration cal;
        double px, py;
        double[] sliceLoc;      // Slice Location (mm) por fatia (1-based -> indice z-1); NaN se ausente
        double sliceSpacing;    // |espacamento| medio entre fatias (mm), estimado das posicoes
        boolean hasSliceLoc;    // true se ha posicoes DICOM utilizaveis
    }

    private static LoadedSeries loadSeries(SeriesData sd) {
        LoadedSeries ls = new LoadedSeries();
        ImageStack stack = new ImageStack();
        Calibration cal = null;
        List<File> files = sd.getFiles();
        double[] loc = new double[files.size()];
        int locCount = 0;

        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            ImagePlus imp = new ImagePlus(f.getAbsolutePath());
            if (imp.getWidth() == 0) { loc[i] = Double.NaN; continue; }
            if (cal == null) cal = imp.getCalibration();
            stack.addSlice(imp.getProcessor());

            // Slice Location (0020,1041) do metadado DICOM da serie.
            double sl = Double.NaN;
            try {
                String meta = sd.getMetadata(f);
                String s = DicomProcessor.getTag(meta, "0020,1041");
                if (s != null) sl = Double.parseDouble(s.trim());
            } catch (Exception ignore) {}
            loc[i] = sl;
            if (!Double.isNaN(sl)) locCount++;
        }

        ls.stack = stack;
        ls.cal = cal;
        ls.px = (cal != null && cal.pixelWidth > 0) ? cal.pixelWidth : 0.5;
        ls.py = (cal != null && cal.pixelHeight > 0) ? cal.pixelHeight : 0.5;
        ls.sliceLoc = loc;
        ls.hasSliceLoc = locCount >= Math.max(2, files.size() / 2);

        // Espacamento medio entre fatias (mediana dos |delta|) a partir das posicoes.
        double spacing = Double.NaN;
        if (ls.hasSliceLoc) {
            List<Double> deltas = new ArrayList<>();
            for (int i = 1; i < loc.length; i++) {
                if (!Double.isNaN(loc[i]) && !Double.isNaN(loc[i - 1])) {
                    deltas.add(Math.abs(loc[i] - loc[i - 1]));
                }
            }
            if (!deltas.isEmpty()) {
                Collections.sort(deltas);
                spacing = deltas.get(deltas.size() / 2);
            }
        }
        // Fallback: pixelDepth da calibracao.
        if (Double.isNaN(spacing) || spacing <= 0) {
            spacing = (cal != null && cal.pixelDepth > 0) ? cal.pixelDepth : 5.0;
        }
        ls.sliceSpacing = spacing;
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

    private static Measure measureRoi(ImageProcessor ip, Calibration cal, Roi roi, String label) {
        ip.setRoi(roi);
        ImageStatistics st = ImageStatistics.getStatistics(
                ip, Measurements.MEAN + Measurements.STD_DEV, cal);
        ip.resetRoi();
        return new Measure(label, st.mean, st.stdDev);
    }

    private static OvalRoi ovalRoi(double cx, double cy, double radiusPx) {
        return new OvalRoi(cx - radiusPx, cy - radiusPx, radiusPx * 2, radiusPx * 2);
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

        // --- 5. Exatidao de distancia entre os pinos de referencia ---
        // Os pinos (pontos claros a 100mm entre si) ficam na regiao homogenea do
        // modulo de baixo contraste. Usa-se as coordenadas de referencia da macro
        // ajustadas pelo centro detectado (sem segmentar, por robustez).
        int pinSlice = (mod2Center > 0) ? mod2Center : mod3Center;
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
        Roi measurementRoi;
        Insert(double cx, double cy, double hu, double sd, Roi measurementRoi) {
            this.cx = cx;
            this.cy = cy;
            this.hu = hu;
            this.sd = sd;
            this.measurementRoi = measurementRoi;
        }
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
            addRoiRecord(r.rois, air.measurementRoi, "CT | Ar interno", r.ctSlice, "CT");
            addRoiRecord(r.rois, poly.measurementRoi, "CT | Polietileno", r.ctSlice, "CT");
            addRoiRecord(r.rois, acr.measurementRoi, "CT | Acrilico", r.ctSlice, "CT");
            addRoiRecord(r.rois, bone.measurementRoi, "CT | Osso", r.ctSlice, "CT");
        }

        // Agua: medida no centro do phantom nesta fatia (inserto de agua/fundo central).
        ImageProcessor ip = ls.stack.getProcessor(best.slice);
        double[] center = phantomCenter(ip, ls.cal);
        double rCt = CT_ROI_RADIUS_MM / ls.px;
        OvalRoi waterRoi = ovalRoi(center[0], center[1], rCt);
        r.water = measureRoi(ip, ls.cal, waterRoi, "Agua");
        addRoiRecord(r.rois, waterRoi, "CT | Agua", r.ctSlice, "CT");

        // Ar no fundo/externo: janela superior, com filtro de HU aplicado no calculo.
        r.airOut = measureAirOutside(ip, ls.cal, ls.px);
        int bandH = (int) Math.min(ip.getHeight() / 4, Math.round(40.0 / ls.px));
        addRoiRecord(r.rois, new Roi(0, 0, ip.getWidth(), bandH),
                "CT | Ar externo (janela filtrada -1050..-800 HU)", r.ctSlice, "CT");
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

            OvalRoi measRoi = ovalRoi(cx, cy, measRadiusPx);
            Measure m = measureRoi(ip, ls.cal, measRoi, "insert");
            inserts.add(new Insert(cx, cy, m.mean, m.sd, measRoi));
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

        OvalRoi centerRoi = ovalRoi(c[0], c[1], rUnif);
        OvalRoi topRoi = ovalRoi(c[0], c[1] - dist, rUnif);
        OvalRoi bottomRoi = ovalRoi(c[0], c[1] + dist, rUnif);
        OvalRoi leftRoi = ovalRoi(c[0] - dist, c[1], rUnif);
        OvalRoi rightRoi = ovalRoi(c[0] + dist, c[1], rUnif);

        r.uCenter = measureRoi(ip, ls.cal, centerRoi, "Centro");
        r.uTop    = measureRoi(ip, ls.cal, topRoi, "Topo (12h)");
        r.uBottom = measureRoi(ip, ls.cal, bottomRoi, "Fundo (6h)");
        r.uLeft   = measureRoi(ip, ls.cal, leftRoi, "Esquerda (9h)");
        r.uRight  = measureRoi(ip, ls.cal, rightRoi, "Direita (3h)");

        addRoiRecord(r.rois, centerRoi, "Uniformidade | Centro", slice, "Uniformidade");
        addRoiRecord(r.rois, topRoi, "Uniformidade | Topo 12h", slice, "Uniformidade");
        addRoiRecord(r.rois, bottomRoi, "Uniformidade | Fundo 6h", slice, "Uniformidade");
        addRoiRecord(r.rois, leftRoi, "Uniformidade | Esquerda 9h", slice, "Uniformidade");
        addRoiRecord(r.rois, rightRoi, "Uniformidade | Direita 3h", slice, "Uniformidade");

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

        OvalRoi backgroundRoi = ovalRoi(c[0], c[1], rCt);
        OvalRoi signalRoi = ovalRoi(c[0], c[1] - signalDist, rCt);
        r.lcBackground = measureRoi(ip, ls.cal, backgroundRoi, "Fundo");
        r.lcSignal = measureRoi(ip, ls.cal, signalRoi, "Massa (sinal)");
        addRoiRecord(r.rois, backgroundRoi, "CNR | Fundo", slice, "CNR");
        addRoiRecord(r.rois, signalRoi, "CNR | Massa sinal", slice, "CNR");

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
    // Coordenadas de referencia das rampas de espessura (da macro do usuario, mm).
    private static final double THK_REF_CENTER_X_MM = 180.282;
    private static final double THK_REF_CENTER_Y_MM = 185.273;
    private static final double THK_RECT_W_MM = 6.328;   // largura do retangulo da rampa
    private static final double THK_RECT_H_MM = 73.0;    // altura do retangulo
    private static final double THK_S1_X_MM = 172.266, THK_S1_Y_MM = 96.328; // rampa superior 1
    private static final double THK_S2_X_MM = 182.812, THK_S2_Y_MM = 96.328; // rampa superior 2

    /**
     * Mede a espessura de corte pelas rampas do Modulo 1, reproduzindo a macro
     * do usuario: NAO segmenta as rampas; usa retangulos em POSICOES DE
     * REFERENCIA (mm), ajustadas pelo deslocamento entre o centro detectado do
     * Modulo 1 e o centro de referencia da secao de espessura. O perfil de cada
     * retangulo e a media das colunas ao longo da altura (equivalente ao
     * getProfile com 'alt' da macro); a espessura e a contagem de picos acima da
     * meia-altura (separacao minima de 3mm). Dois retangulos superiores e dois
     * inferiores (estes espelhados em Y, como na macro).
     */
    private static void measureSliceThickness(LoadedSeries ls, AcrQcResult r) {
        if (r.mod1Center < 1 || r.mod1Center > ls.stack.getSize()) return;
        ImageProcessor ip = ls.stack.getProcessor(r.mod1Center);
        double[] cPx = phantomCenter(ip, ls.cal);
        double cxMm = cPx[0] * ls.px, cyMm = cPx[1] * ls.py;

        // Offset entre o centro detectado e o centro de referencia da macro.
        double offXmm = cxMm - THK_REF_CENTER_X_MM;
        double offYmm = cyMm - THK_REF_CENTER_Y_MM;

        double wPx = THK_RECT_W_MM / ls.px;
        int hPx = (int) Math.round(THK_RECT_H_MM / ls.py);

        // Rampas superiores (como na macro).
        double xS1 = (THK_S1_X_MM + offXmm) / ls.px;
        double xS2 = (THK_S2_X_MM + offXmm) / ls.px;
        double yS  = (THK_S1_Y_MM + offYmm) / ls.py;

        // Rampas inferiores: Y espelhado em relacao ao centro de referencia,
        // rI1_Y = refThickY + (refThickY - (rS1_Y + rH)).
        double rI1Ymm = THK_REF_CENTER_Y_MM + (THK_REF_CENTER_Y_MM - (THK_S1_Y_MM + THK_RECT_H_MM));
        double yI = (rI1Ymm + offYmm) / ls.py;

        Roi roiS1 = new Roi(xS1, yS, wPx, hPx);
        Roi roiS2 = new Roi(xS2, yS, wPx, hPx);
        Roi roiI1 = new Roi(xS1, yI, wPx, hPx);
        Roi roiI2 = new Roi(xS2, yI, wPx, hPx);

        double[] profS1 = rectColumnProfile(ip, ls.cal, roiS1.getBounds());
        double[] profS2 = rectColumnProfile(ip, ls.cal, roiS2.getBounds());
        double[] profI1 = rectColumnProfile(ip, ls.cal, roiI1.getBounds());
        double[] profI2 = rectColumnProfile(ip, ls.cal, roiI2.getBounds());

        addRoiRecord(r.rois, roiS1, "Espessura | Rampa superior 1", r.mod1Center, "Espessura");
        addRoiRecord(r.rois, roiS2, "Espessura | Rampa superior 2", r.mod1Center, "Espessura");
        addRoiRecord(r.rois, roiI1, "Espessura | Rampa inferior 1", r.mod1Center, "Espessura");
        addRoiRecord(r.rois, roiI2, "Espessura | Rampa inferior 2", r.mod1Center, "Espessura");

        double maxS = Math.max(arrMax(profS1), arrMax(profS2));
        int pS1 = countPeaks(profS1, maxS, ls.py);
        int pS2 = countPeaks(profS2, maxS, ls.py);

        double maxI = Math.max(arrMax(profI1), arrMax(profI2));
        int pI1 = countPeaks(profI1, maxI, ls.py);
        int pI2 = countPeaks(profI2, maxI, ls.py);

        r.sliceThicknessTop = (pS1 + pS2) / 2.0;
        r.sliceThicknessBottom = (pI1 + pI2) / 2.0;
    }

    /**
     * Perfil vertical de um retangulo: para cada linha (y), media dos pixels ao
     * longo da largura (x). Equivale ao getProfile de um retangulo vertical no
     * ImageJ (media das colunas), usado na macro com a tecla 'alt'.
     */
    private static double[] rectColumnProfile(ImageProcessor ip, Calibration cal, Rectangle bounds) {
        int x0 = bounds.x;
        int y0 = bounds.y;
        int w = Math.max(1, bounds.width);
        double[] prof = new double[Math.max(1, bounds.height)];
        for (int k = 0; k < prof.length; k++) {
            int yi = y0 + k;
            double sum = 0; int n = 0;
            for (int xi = x0; xi < x0 + w; xi++) {
                if (xi < 0 || xi >= ip.getWidth() || yi < 0 || yi >= ip.getHeight()) continue;
                sum += (cal != null) ? cal.getCValue(ip.getf(xi, yi)) : ip.getf(xi, yi);
                n++;
            }
            prof[k] = (n > 0) ? sum / n : -1000;
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

    // Coordenadas de referencia dos pinos (da macro do usuario), em mm na
    // imagem. A distancia nominal t1-t2 e ~100mm.
    private static final double PIN_REF_CENTER_X_MM = 125.051;
    private static final double PIN_REF_CENTER_Y_MM = 124.801;
    private static final double PIN_T1_X_MM = 139.16, PIN_T1_Y_MM = 110.35;
    private static final double PIN_T2_X_MM = 68.36,  PIN_T2_Y_MM = 180.66;
    private static final double PIN_SEARCH_RADIUS_MM = 5.0;

    /**
     * Mede a distancia entre os dois pinos de referencia (pontos claros na
     * regiao homogenea, nominalmente a 100mm entre si).
     *
     * Reproduz a abordagem da macro do usuario: NAO segmenta os pinos; usa as
     * coordenadas de referencia (t1, t2) ajustadas pelo deslocamento entre o
     * centro detectado do phantom e o centro de referencia, e faz uma busca
     * local de maximo de HU (janela de 5mm) para travar no pino.
     *
     * VERIFICACAO DE ORIENTACAO/INVERSAO (pedido do usuario): alem do par
     * nominal (t1,t2), testa tambem o par ESPELHADO em relacao ao centro
     * (t1',t2'), que corresponde ao phantom invertido/rotacionado 180 graus.
     * Escolhe o par cuja distancia medida fica mais proxima dos 100mm nominais e
     * sinaliza quando foi o par espelhado que venceu (phantom possivelmente
     * invertido).
     */
    private static void measurePinDistance(LoadedSeries ls, AcrQcResult r, int slice) {
        if (slice < 1 || slice > ls.stack.getSize()) { r.pinNote = "fatia de pinos invalida"; return; }
        ImageProcessor ip = ls.stack.getProcessor(slice);
        double[] cPx = phantomCenter(ip, ls.cal);
        double cxMm = cPx[0] * ls.px, cyMm = cPx[1] * ls.py;

        // Offset entre o centro detectado e o centro de referencia da macro.
        double offX = cxMm - PIN_REF_CENTER_X_MM;
        double offY = cyMm - PIN_REF_CENTER_Y_MM;

        // Fundo local da regiao (mediana aproximada via media central) para medir
        // o contraste dos pinos acima do fundo.
        OvalRoi pinBackgroundRoi = ovalRoi(cPx[0], cPx[1], 10.0 / ls.px);
        double bg = measureRoi(ip, ls.cal, pinBackgroundRoi, "bg").mean;
        addRoiRecord(r.rois, pinBackgroundRoi, "Distancia | Fundo local", slice, "Distancia");

        // Par nominal (orientacao esperada).
        double n1x = (PIN_T1_X_MM + offX) / ls.px;
        double n1y = (PIN_T1_Y_MM + offY) / ls.py;
        double n2x = (PIN_T2_X_MM + offX) / ls.px;
        double n2y = (PIN_T2_Y_MM + offY) / ls.py;
        addRoiRecord(r.rois, pinSearchWindow(ls, n1x, n1y), "Distancia | Busca nominal P1", slice, "Distancia");
        addRoiRecord(r.rois, pinSearchWindow(ls, n2x, n2y), "Distancia | Busca nominal P2", slice, "Distancia");
        double[] n1 = localMaxHU(ip, ls, n1x, n1y);
        double[] n2 = localMaxHU(ip, ls, n2x, n2y);
        double distNominal = Math.hypot((n2[0] - n1[0]) * ls.px, (n2[1] - n1[1]) * ls.py);
        double contrNominal = Math.min(n1[2] - bg, n2[2] - bg); // contraste do pino mais fraco

        // Par espelhado em relacao ao centro de referencia (phantom invertido 180).
        double t1xInv = 2 * PIN_REF_CENTER_X_MM - PIN_T1_X_MM;
        double t1yInv = 2 * PIN_REF_CENTER_Y_MM - PIN_T1_Y_MM;
        double t2xInv = 2 * PIN_REF_CENTER_X_MM - PIN_T2_X_MM;
        double t2yInv = 2 * PIN_REF_CENTER_Y_MM - PIN_T2_Y_MM;
        double i1x = (t1xInv + offX) / ls.px;
        double i1y = (t1yInv + offY) / ls.py;
        double i2x = (t2xInv + offX) / ls.px;
        double i2y = (t2yInv + offY) / ls.py;
        addRoiRecord(r.rois, pinSearchWindow(ls, i1x, i1y), "Distancia | Busca espelhada P1", slice, "Distancia");
        addRoiRecord(r.rois, pinSearchWindow(ls, i2x, i2y), "Distancia | Busca espelhada P2", slice, "Distancia");
        double[] i1 = localMaxHU(ip, ls, i1x, i1y);
        double[] i2 = localMaxHU(ip, ls, i2x, i2y);
        double distInverted = Math.hypot((i2[0] - i1[0]) * ls.px, (i2[1] - i1[1]) * ls.py);
        double contrInverted = Math.min(i1[2] - bg, i2[2] - bg);

        // A ESCOLHA do par correto baseia-se no CONTRASTE dos pinos (os pinos
        // reais sao pontos claros, bem acima do fundo). A inversao so e
        // sinalizada quando o par ESPELHADO tem contraste claramente maior que o
        // nominal -- indicio real de phantom invertido. Se os contrastes forem
        // semelhantes (pinos simetricos), usa-se o par nominal sem alarme.
        r.pinDistanceH = distNominal;   // H = par nominal
        r.pinDistanceV = distInverted;  // V = par espelhado
        r.pinMarkerCount = 2;

        double[] chosen1;
        double[] chosen2;
        double CONTRAST_MARGIN = 10.0; // HU
        if (contrInverted > contrNominal + CONTRAST_MARGIN) {
            r.pinDistance = distInverted;
            r.pinOrientation = "espelhado";
            r.pinPhantomInverted = true;
            chosen1 = i1;
            chosen2 = i2;
            r.pinNote = String.format(java.util.Locale.US,
                    "nominal=%.1f mm (contraste %.0f HU) | espelhado=%.1f mm (contraste %.0f HU) | "
                    + "usado: espelhado (VERIFICAR: phantom possivelmente invertido/rotacionado)",
                    distNominal, contrNominal, distInverted, contrInverted);
        } else {
            r.pinDistance = distNominal;
            r.pinOrientation = "nominal";
            r.pinPhantomInverted = false;
            chosen1 = n1;
            chosen2 = n2;
            String warn = (contrNominal < 5.0) ? " (pinos com baixo contraste \u2014 conferir)" : "";
            r.pinNote = String.format(java.util.Locale.US,
                    "nominal=%.1f mm (contraste %.0f HU) | espelhado=%.1f mm (contraste %.0f HU) | usado: nominal%s",
                    distNominal, contrNominal, distInverted, contrInverted, warn);
        }

        addRoiRecord(r.rois, new PointRoi(chosen1[0], chosen1[1]),
                "Distancia | Pino 1 escolhido", slice, "Distancia");
        addRoiRecord(r.rois, new PointRoi(chosen2[0], chosen2[1]),
                "Distancia | Pino 2 escolhido", slice, "Distancia");
        addRoiRecord(r.rois, new Line(chosen1[0], chosen1[1], chosen2[0], chosen2[1]),
                String.format(java.util.Locale.US, "Distancia | Linha medida %.2f mm", r.pinDistance),
                slice, "Distancia");
    }

    private static Roi pinSearchWindow(LoadedSeries ls, double gx, double gy) {
        int cx = (int) Math.round(gx);
        int cy = (int) Math.round(gy);
        int sr = (int) Math.round(PIN_SEARCH_RADIUS_MM / ls.px);
        return new Roi(cx - sr, cy - sr, 2 * sr + 1, 2 * sr + 1);
    }

    /** Busca o maximo de HU numa janela de 5mm ao redor de (gx,gy) em pixels. */
    private static double[] localMaxHU(ImageProcessor ip, LoadedSeries ls, double gx, double gy) {
        int cx = (int) Math.round(gx), cy = (int) Math.round(gy);
        int sr = (int) Math.round(PIN_SEARCH_RADIUS_MM / ls.px);
        double best = -Double.MAX_VALUE; int bx = cx, by = cy;
        for (int y = cy - sr; y <= cy + sr; y++) {
            for (int x = cx - sr; x <= cx + sr; x++) {
                if (x < 0 || x >= ip.getWidth() || y < 0 || y >= ip.getHeight()) continue;
                double hu = (ls.cal != null) ? ls.cal.getCValue(ip.getf(x, y)) : ip.getf(x, y);
                if (hu > best) { best = hu; bx = x; by = y; }
            }
        }
        return new double[]{bx, by, best};
    }

    // ------------------------------------------------------------------
    // Linha unica para o Excel (TAB-separada, decimal com virgula)
    // ------------------------------------------------------------------

    private static String fmt(double v) {
        if (Double.isNaN(v)) return "N/A";
        return String.format(java.util.Locale.US, "%.2f", v).replace('.', ',');
    }

    private static String fmtMean(Measure m) {
        return m != null && m.valid ? fmt(m.mean) : "N/A";
    }

    private static String fmtSd(Measure m) {
        return m != null && m.valid ? fmt(m.sd) : "N/A";
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

        // NPS (fatias centrais do modulo uniforme, afastadas das interfaces)
        public double[] npsFreq;
        public double[] npsData;
        public double[][] nps2D;
        public double npsVariance = Double.NaN;
        public double npsIntegrated = Double.NaN;
        public double npsPeakFreq = Double.NaN;
        public double npsMeanFreq = Double.NaN;
        public int npsSlicesUsed = 0;
        public int npsRoisUsed = 0;
        public int ttfSlicesUsed = 0;
        public double npsRoiSizeMm = NPS_ROI_SIZE_MM;
        public double npsDistributionRadiusMm = NPS_DIST_RADIUS_MM;
        public final List<RoiRecord> rois = new ArrayList<>();

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
     *   - NPS nas fatias centrais do modulo uniforme, afastadas das interfaces,
     *     com ROIs quadradas de 30mm distribuidas em um raio de 60mm.
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

        // --- NPS: fatias uniformes limpas (classe UNIFORM), DESCARTANDO as de
        //     borda contaminadas por volume parcial do modulo vizinho (beads).
        //     As bordas tem ruido central muito acima da mediana -> removidas ---
        List<Integer> uniformSlices = new ArrayList<>(mm.uniformSlices);
        Collections.sort(uniformSlices);
        if (uniformSlices.isEmpty() && !mm.beadsSlices.isEmpty()) {
            int bs = Collections.min(mm.beadsSlices), be = Collections.max(mm.beadsSlices);
            uniformSlices = filterUniformitySlices(ls.stack, bs, be, ls.cal, ls.px, ls.py);
        }
        uniformSlices = rejectContaminatedSlices(ls, uniformSlices);
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

    // Margem de seguranca (mm) para afastar as fatias de NPS das INTERFACES
    // entre modulos do phantom. Cada modulo do ACR tem 40mm; proximo das
    // interfaces ha volume parcial do modulo vizinho (ex.: beads) que contamina
    // o ruido. Descartamos as fatias a menos desta margem das bordas do bloco.
    private static final double MODULE_INTERFACE_MARGIN_MM = 7.0;

    /**
     * Descarta as fatias de uniformidade proximas das INTERFACES entre modulos,
     * usando o Slice Location do DICOM e as dimensoes conhecidas do phantom. E
     * mais robusto que inferir contaminacao por estatistica de ruido: as fatias
     * de borda (volume parcial do modulo vizinho) sao removidas deterministicamente.
     *
     * Quando nao ha Slice Location, cai no espacamento de fatia (pixelDepth) e,
     * em ultimo caso, apenas apara a primeira e a ultima fatia do bloco.
     */
    static List<Integer> rejectContaminatedSlices(LoadedSeries ls, List<Integer> slices) {
        if (slices == null || slices.size() <= 2) return slices;
        List<Integer> sorted = new ArrayList<>(slices);
        Collections.sort(sorted);

        if (ls.hasSliceLoc) {
            // Posicoes (mm) das fatias do bloco; bordas = interfaces com vizinhos.
            double locFirst = ls.sliceLoc[sorted.get(0) - 1];
            double locLast  = ls.sliceLoc[sorted.get(sorted.size() - 1) - 1];
            double lo = Math.min(locFirst, locLast);
            double hi = Math.max(locFirst, locLast);

            List<Integer> kept = new ArrayList<>();
            for (int z : sorted) {
                double loc = ls.sliceLoc[z - 1];
                if (Double.isNaN(loc)) continue;
                // Mantem apenas fatias suficientemente distantes das duas interfaces.
                if ((loc - lo) >= MODULE_INTERFACE_MARGIN_MM
                        && (hi - loc) >= MODULE_INTERFACE_MARGIN_MM) {
                    kept.add(z);
                }
            }
            if (!kept.isEmpty()) return kept;
            // Se a margem eliminou tudo (bloco fino), cai no aparo simples abaixo.
        }

        // Fallback sem posicoes DICOM utilizaveis: usa o espacamento para estimar
        // quantas fatias aparar em cada extremidade (>= 1).
        int trim = Math.max(1, (int) Math.round(MODULE_INTERFACE_MARGIN_MM / Math.max(0.1, ls.sliceSpacing)));
        if (sorted.size() > 2 * trim + 1) {
            return new ArrayList<>(sorted.subList(trim, sorted.size() - trim));
        }
        // Bloco pequeno: apara so a primeira e a ultima.
        return new ArrayList<>(sorted.subList(1, sorted.size() - 1));
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
            q.ttfSlicesUsed = slices.size();

            // Guarda os contornos efetivamente usados pela TTF, traduzindo a
            // posicao local do substack para a fatia global da serie.
            for (int insertId = 0; insertId < det.rois.size(); insertId++) {
                List<Roi> sliceRois = det.rois.get(insertId);
                if (sliceRois == null) continue;
                int count = Math.min(sliceRois.size(), slices.size());
                for (int j = 0; j < count; j++) {
                    addRoiRecord(q.rois, sliceRois.get(j),
                            String.format(java.util.Locale.US, "TTF | Inserto %02d | contorno", insertId + 1),
                            slices.get(j), "TTF");
                }
            }

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
        int validSlices = 0;
        double sumVariance = 0.0;

        for (int z : slices) {
            int recordsBeforeSlice = validRecords;
            ImageProcessor ip = ls.stack.getProcessor(z);
            PolygonRoi phantom = Segmenter.segmenter(ip, ls.cal, -200, 5000, 0.2, false, 0, 20);
            if (phantom == null) continue;
            addRoiRecord(q.rois, phantom, "NPS | Contorno do phantom", z, "NPS");
            List<Roi> records = Segmenter.createCircularRecordRois(
                    ip, ls.cal, phantom, NPS_NUM_ROIS, roiSizePx, distRadiusPx, false, z);

            int roiNumber = 0;
            for (Roi rec : records) {
                roiNumber++;
                ip.setRoi(rec);
                ImageProcessor crop = ip.crop();
                ip.resetRoi();
                if (crop.getWidth() != roiSizePx || crop.getHeight() != roiSizePx) continue;

                addRoiRecord(q.rois, rec,
                        String.format(java.util.Locale.US, "NPS | ROI %02d", roiNumber), z, "NPS");

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
            if (validRecords > recordsBeforeSlice) validSlices++;
        }

        if (validRecords == 0) { q.message = "Nenhum ROI valido para NPS."; return; }
        q.npsSlicesUsed = validSlices;
        q.npsRoisUsed = validRecords;
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
          .append(fmtMean(r.airIn)).append(T).append(fmtSd(r.airIn)).append(T)
          .append(fmtMean(r.poly)).append(T).append(fmtSd(r.poly)).append(T)
          .append(fmtMean(r.water)).append(T).append(fmtSd(r.water)).append(T)
          .append(fmtMean(r.acrylic)).append(T).append(fmtSd(r.acrylic)).append(T)
          .append(fmtMean(r.bone)).append(T).append(fmtSd(r.bone)).append(T)
          .append(fmtMean(r.airOut)).append(T).append(fmtSd(r.airOut)).append(T)
          .append(fmtMean(r.lcSignal)).append(T)
          .append(fmtMean(r.lcBackground)).append(T).append(fmtSd(r.lcBackground)).append(T)
          .append(fmt(r.cnr)).append(T)
          .append(fmtMean(r.uTop)).append(T).append(fmtSd(r.uTop)).append(T)
          .append(fmtMean(r.uBottom)).append(T).append(fmtSd(r.uBottom)).append(T)
          .append(fmtMean(r.uLeft)).append(T).append(fmtSd(r.uLeft)).append(T)
          .append(fmtMean(r.uRight)).append(T).append(fmtSd(r.uRight)).append(T)
          .append(fmtMean(r.uCenter)).append(T).append(fmtSd(r.uCenter)).append(T)
          .append(fmt(r.noise)).append(T)
          .append(fmt(r.pinDistance));
        return sb.toString();
    }
}
