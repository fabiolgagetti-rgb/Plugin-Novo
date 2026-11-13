import java.io.File;
import java.util.Arrays;

import br.rededor.fisicamedica.AcrQcAnalyser;
import br.rededor.fisicamedica.SeriesData;
import ij.plugin.DICOM;

/**
 * Validacao do motor de CQ ({@link AcrQcAnalyser}) contra a serie REAL
 * CRANIO S_C. Monta um SeriesData exatamente como o plugin faz (incluindo a
 * ordenacao por Instance Number), executa analyze() e runImageQualityPipeline()
 * e imprime todos os resultados.
 *
 * Roda via: java -cp <classes>:<deps> AcrRealValidation <dir>
 */
public class AcrRealValidation {

    public static void main(String[] args) {
        String dir = args.length > 0 ? args[0] : "/projects/sandbox/testdata/CRANIO S_C";
        File d = new File(dir);
        File[] files = d.listFiles();
        if (files == null) { System.out.println("Dir nao encontrado: " + dir); return; }
        Arrays.sort(files);

        // Monta SeriesData com metadados, como o DicomProcessor faz.
        SeriesData sd = new SeriesData("CRANIO S/C", "test-uid");
        DICOM dcm = new DICOM();
        for (File f : files) {
            String meta = dcm.getInfo(f.getAbsolutePath());
            sd.addFile(f, meta);
        }
        // Ordena por Instance Number (como o plugin faz ao montar a arvore).
        sd.sortFileMetadataByInstanceNumber();

        System.out.println("Arquivos na ordem de analise (apos sort por InstanceNumber):");
        int idx = 1;
        for (File f : sd.getFiles()) {
            System.out.print(f.getName());
            System.out.print(idx % 10 == 0 ? "\n" : "  ");
            idx++;
        }
        System.out.println("\n");

        // --- Testes ANVISA ---
        AcrQcAnalyser.AcrQcResult r = AcrQcAnalyser.analyze(sd);
        System.out.println("==================== TESTES ANVISA ====================");
        if (!r.found) {
            System.out.println("FALHOU: " + r.message);
        } else {
            System.out.printf("Phantom: fatias %d..%d | Mod1 centro=%d%n", r.phantomStart, r.phantomEnd, r.mod1Center);
            System.out.printf("Fatia nº CT=%d | Uniformidade=%d | Baixo contraste=%d%n%n",
                    r.ctSlice, r.uniformitySlice, r.lowContrastSlice);

            System.out.println("-- Espessura de corte --");
            System.out.printf("  Nominal(DICOM)=%s | Sup=%s | Inf=%s%n",
                    fmt(r.sliceThicknessDicom), fmt(r.sliceThicknessTop), fmt(r.sliceThicknessBottom));

            System.out.println("-- Exatidao numero de CT --");
            pm("  Agua       ", r.water);
            pm("  Osso       ", r.bone);
            pm("  Ar interno ", r.airIn);
            pm("  Ar fundo   ", r.airOut);
            pm("  Polietileno", r.poly);
            pm("  Acrilico   ", r.acrylic);

            System.out.println("-- Uniformidade e ruido --");
            pm("  Centro     ", r.uCenter);
            pm("  Topo 12h   ", r.uTop);
            pm("  Fundo 6h   ", r.uBottom);
            pm("  Esq 9h     ", r.uLeft);
            pm("  Dir 3h     ", r.uRight);
            System.out.printf("  MaxDif=%s | Ruido=%s%n", fmt(r.maxUniformityDiff), fmt(r.noise));

            System.out.println("-- Baixo contraste / CNR --");
            pm("  Sinal      ", r.lcSignal);
            pm("  Fundo      ", r.lcBackground);
            System.out.printf("  CNR=%s%n", fmt(r.cnr));

            System.out.println("-- Exatidao de distancia (pinos) --");
            System.out.printf("  Fatia pinos=%d | marcadores=%d%n", r.pinSlice, r.pinMarkerCount);
            System.out.printf("  Distancia=%s mm (par %s) | H=%s V=%s%n",
                    fmt(r.pinDistance), r.pinOrientation, fmt(r.pinDistanceH), fmt(r.pinDistanceV));
            System.out.printf("  Inversao? %s%n", r.pinPhantomInverted ? "SIM (verificar)" : "nao");
            System.out.println("  Nota: " + r.pinNote);

            System.out.println("\n-- Linha Excel --");
            System.out.println(r.excelLine);
        }

        // --- Pipeline de qualidade de imagem ---
        System.out.println("\n==================== QUALIDADE DE IMAGEM ====================");
        double[] eye = {500.0, 305.0, 250.0};
        AcrQcAnalyser.ImageQualityResult q = AcrQcAnalyser.runImageQualityPipeline(sd, eye);
        if (!q.ok) {
            System.out.println("FALHOU: " + q.message);
        } else {
            System.out.printf("NPS: %d fatias uniformes usadas%n", q.npsSlicesUsed);
            System.out.printf("NPS: variancia=%s integrado=%s picoFreq=%s mediaFreq=%s%n",
                    fmt(q.npsVariance), fmt(q.npsIntegrated), fmt(q.npsPeakFreq), fmt(q.npsMeanFreq));
            System.out.printf("TTF: insert de menor contraste id=%d (pontos TTF=%d)%n",
                    q.lowestContrastInsertId, q.ttfDataSel != null ? q.ttfDataSel.length : 0);
            System.out.printf("d'(NPWE)=%s | AUC=%s | tarefa=%.0fmm diam, %.0f HU%n",
                    fmt(q.dPrime), fmt(q.auc), q.taskRadiusMm * 2, q.taskContrastHU);
            if (q.message != null && !q.message.isEmpty())
                System.out.println("msg: " + q.message);
        }
    }

    static void pm(String label, AcrQcAnalyser.Measure m) {
        if (m == null || !m.valid) { System.out.println(label + ": N/A"); return; }
        System.out.printf("%s: %8.2f +/- %6.2f HU%n", label, m.mean, m.sd);
    }

    static String fmt(double v) {
        return Double.isNaN(v) ? "N/A" : String.format(java.util.Locale.US, "%.3f", v);
    }
}
