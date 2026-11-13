package br.rededor.fisicamedica;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import ij.IJ;

/**
 * Aba "CQ ACR": controle de qualidade automatizado do phantom ACR de TC.
 *
 * - Exibe as principais informacoes DICOM da serie ativa (nome, protocolo,
 *   kernel, espessura de corte, kV, mA, tempo de rotacao, pitch).
 * - Botao "Analisar" que executa os testes ANVISA (espessura de corte,
 *   exatidao do numero de CT, uniformidade, ruido, CNR de baixo contraste e
 *   exatidao de distancia entre pinos) e, em paralelo, o pipeline de qualidade
 *   de imagem (TTF, NPS, NPWE/d').
 * - Os resultados de TTF/NPS/d' sao PUBLICADOS em seus respectivos paineis
 *   (abas TTF, NPS e NPWE Model Observer); esta aba apenas resume o d'.
 * - Mostra os resultados e uma linha unica (TAB-separada, decimal com virgula)
 *   pronta para colar no Excel.
 */
public class AcrQcPanel {

    private static JLabel lblPatient, lblProtocol, lblKernel, lblThickness, lblKvp, lblMa,
            lblRotTime, lblPitch;
    private static JTextArea resultsArea;
    private static JTextArea excelArea;
    private static JButton analyzeBtn, refreshBtn, copyBtn;

    public static JPanel create() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // ---------------- Cabecalho: informacoes DICOM ----------------
        JPanel header = new JPanel(new GridBagLayout());
        header.setBorder(BorderFactory.createTitledBorder("Informacoes da aquisicao (DICOM)"));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 6, 3, 6);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;

        lblPatient   = valueLabel();
        lblProtocol  = valueLabel();
        lblKernel    = valueLabel();
        lblThickness = valueLabel();
        lblKvp       = valueLabel();
        lblMa        = valueLabel();
        lblRotTime   = valueLabel();
        lblPitch     = valueLabel();

        int row = 0;
        row = addHeaderRow(header, g, row, "Paciente/Serie:", lblPatient, "Protocolo:", lblProtocol);
        row = addHeaderRow(header, g, row, "Kernel:", lblKernel, "Espessura de corte:", lblThickness);
        row = addHeaderRow(header, g, row, "kVp:", lblKvp, "mA:", lblMa);
        row = addHeaderRow(header, g, row, "Tempo de rotacao:", lblRotTime, "Pitch:", lblPitch);

        refreshBtn = new JButton("Atualizar infos");
        analyzeBtn = new JButton("Analisar");
        analyzeBtn.setFont(analyzeBtn.getFont().deriveFont(Font.BOLD));
        JPanel btnRow = new JPanel();
        btnRow.add(refreshBtn);
        btnRow.add(analyzeBtn);

        g.gridx = 0; g.gridy = row; g.gridwidth = 4; g.anchor = GridBagConstraints.CENTER;
        g.fill = GridBagConstraints.NONE;
        header.add(btnRow, g);

        // ---------------- Resultados ----------------
        resultsArea = new JTextArea(22, 48);
        resultsArea.setEditable(false);
        resultsArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane resultsScroll = new JScrollPane(resultsArea);
        resultsScroll.setBorder(BorderFactory.createTitledBorder("Resultados do Controle de Qualidade"));

        // ---------------- Linha para Excel ----------------
        excelArea = new JTextArea(3, 48);
        excelArea.setEditable(false);
        excelArea.setLineWrap(true);
        excelArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        JScrollPane excelScroll = new JScrollPane(excelArea);

        copyBtn = new JButton("Copiar linha para o Excel");
        JPanel excelPanel = new JPanel(new BorderLayout(4, 4));
        excelPanel.setBorder(BorderFactory.createTitledBorder("Linha para o relatorio (Excel) \u2014 TAB-separada, decimal com virgula"));
        excelPanel.add(excelScroll, BorderLayout.CENTER);
        excelPanel.add(copyBtn, BorderLayout.SOUTH);

        JPanel bottom = new JPanel(new BorderLayout(6, 6));
        bottom.add(resultsScroll, BorderLayout.CENTER);
        bottom.add(excelPanel, BorderLayout.SOUTH);

        root.add(header, BorderLayout.NORTH);
        root.add(bottom, BorderLayout.CENTER);
        root.setPreferredSize(new Dimension(820, 600));

        // ---------------- Acoes ----------------
        refreshBtn.addActionListener(e -> refreshDicomInfo());
        analyzeBtn.addActionListener(e -> runAnalysis());
        copyBtn.addActionListener(e -> {
            String txt = excelArea.getText();
            if (txt != null && !txt.isEmpty()) {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(txt), null);
                IJ.showStatus("Linha copiada para a area de transferencia.");
            }
        });

        refreshDicomInfo();
        return root;
    }

    private static JLabel valueLabel() {
        JLabel l = new JLabel("-");
        l.setFont(l.getFont().deriveFont(Font.PLAIN));
        return l;
    }

    private static int addHeaderRow(JPanel p, GridBagConstraints g, int row,
                                    String k1, JLabel v1, String k2, JLabel v2) {
        g.gridwidth = 1;
        g.gridx = 0; g.gridy = row; g.weightx = 0; p.add(boldLabel(k1), g);
        g.gridx = 1; g.weightx = 1; p.add(v1, g);
        g.gridx = 2; g.weightx = 0; p.add(boldLabel(k2), g);
        g.gridx = 3; g.weightx = 1; p.add(v2, g);
        return row + 1;
    }

    private static JLabel boldLabel(String s) {
        JLabel l = new JLabel(s);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
    }

    // ------------------------------------------------------------------
    // Infos DICOM
    // ------------------------------------------------------------------

    private static void refreshDicomInfo() {
        SeriesData sd = ImageOpener.getActiveSeriesData();
        if (sd == null || sd.getFiles().isEmpty()) {
            setInfo("(nenhuma serie ativa)", "-", "-", "-", "-", "-", "-", "-");
            return;
        }
        File f = sd.getFiles().get(0);
        String meta = sd.getMetadata(f);

        String patient  = sd.getSeriesDescription();
        String protocol = tag(meta, "0018,1030");
        String kernel   = tag(meta, "0018,1210");
        String thick    = tag(meta, "0018,0050");
        String kvp      = tag(meta, "0018,0060");
        String ma       = tag(meta, "0018,1151");
        if (isBlank(ma)) ma = tag(meta, "0018,1152"); // Exposure (mAs) como fallback
        // Tempo de rotacao: Revolution Time (0018,9305) ou Exposure Time (0018,1150)
        String rot      = tag(meta, "0018,9305");
        if (isBlank(rot)) rot = tag(meta, "0018,1150");
        // Pitch: Spiral Pitch Factor (0018,9311)
        String pitch    = tag(meta, "0018,9311");

        setInfo(patient,
                orDash(protocol),
                orDash(kernel),
                isBlank(thick) ? "-" : thick + " mm",
                isBlank(kvp) ? "-" : kvp + " kV",
                orDash(ma),
                isBlank(rot) ? "-" : rot + " ms/s",
                orDash(pitch));
    }

    private static void setInfo(String patient, String protocol, String kernel, String thickness,
                                String kvp, String ma, String rot, String pitch) {
        lblPatient.setText(patient);
        lblProtocol.setText(protocol);
        lblKernel.setText(kernel);
        lblThickness.setText(thickness);
        lblKvp.setText(kvp);
        lblMa.setText(ma);
        lblRotTime.setText(rot);
        lblPitch.setText(pitch);
    }

    private static String tag(String meta, String t) {
        return DicomProcessor.getTag(meta, t);
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
    private static String orDash(String s) { return isBlank(s) ? "-" : s.trim(); }

    // ------------------------------------------------------------------
    // Analise
    // ------------------------------------------------------------------

    private static void runAnalysis() {
        final SeriesData sd = ImageOpener.getActiveSeriesData();
        if (sd == null || sd.getFiles().isEmpty()) {
            JOptionPane.showMessageDialog(null,
                    "Nenhuma serie ativa. Abra as imagens do phantom ACR no File Browser (duplo-clique na serie).",
                    "CQ ACR", JOptionPane.WARNING_MESSAGE);
            return;
        }

        analyzeBtn.setEnabled(false);
        resultsArea.setText("Analisando... (localizando modulos, medindo CQ, calculando TTF/NPS/d')\n");
        excelArea.setText("");

        // Captura os parametros do eye filter na EDT antes de ir para o background.
        final double[] eyeFilter = DetectabilityIndexPanel.getEyeFilterParams();

        new Thread(() -> {
            try {
                AcrQcAnalyser.AcrQcResult qc = AcrQcAnalyser.analyze(sd);
                AcrQcAnalyser.ImageQualityResult iq = AcrQcAnalyser.runImageQualityPipeline(sd, eyeFilter);

                SwingUtilities.invokeLater(() -> {
                    publishImageQuality(iq);
                    resultsArea.setText(formatResults(qc, iq));
                    excelArea.setText(qc.found ? qc.excelLine : "");
                    analyzeBtn.setEnabled(true);
                });
            } catch (Exception ex) {
                final String msg = ex.getMessage();
                SwingUtilities.invokeLater(() -> {
                    resultsArea.setText("Erro durante a analise: " + msg);
                    analyzeBtn.setEnabled(true);
                });
                IJ.handleException(ex);
            }
        }).start();
    }

    /** Publica TTF/NPS/d' nos paineis respectivos (mantendo os resultados la). */
    private static void publishImageQuality(AcrQcAnalyser.ImageQualityResult iq) {
        if (iq == null || !iq.ok) return;

        if (iq.ttf != null && iq.ttf.ttfs != null && !iq.ttf.ttfs.isEmpty()) {
            TtfPanel.publishExternalResult(iq.ttf, iq.cal);
        }
        if (iq.npsData != null && iq.npsFreq != null) {
            NPSpanel.publishExternalResult(iq.npsFreq, iq.npsData, iq.nps2D,
                    iq.npsVariance, iq.npsIntegrated, iq.npsPeakFreq, iq.npsMeanFreq);
        }
        if (!Double.isNaN(iq.dPrime)) {
            DetectabilityIndexPanel.publishExternalResult(
                    iq.dPrime, iq.auc, iq.taskRadiusMm, iq.taskContrastHU,
                    iq.ttfFreqSel, iq.ttfDataSel, iq.npsFreq, iq.npsData, iq.nps2D);
        }
    }

    // ------------------------------------------------------------------
    // Formatacao dos resultados
    // ------------------------------------------------------------------

    private static String m(AcrQcAnalyser.Measure x) {
        if (x == null || !x.valid) return "N/A";
        return String.format(java.util.Locale.US, "%.2f +/- %.2f HU", x.mean, x.sd);
    }

    private static String d(double v, String unit) {
        if (Double.isNaN(v)) return "N/A";
        return String.format(java.util.Locale.US, "%.2f %s", v, unit).trim();
    }

    private static String formatResults(AcrQcAnalyser.AcrQcResult r, AcrQcAnalyser.ImageQualityResult iq) {
        StringBuilder sb = new StringBuilder();
        if (!r.found) {
            sb.append("Falha na analise de CQ: ").append(r.message).append("\n");
        } else {
            sb.append("=== GEOMETRIA DETECTADA ===\n");
            sb.append(String.format("Phantom: fatias %d a %d | Modulo 1 (centro): %d%n",
                    r.phantomStart, r.phantomEnd, r.mod1Center));
            sb.append(String.format("Fatia nº CT: %d | Uniformidade: %d | Baixo contraste: %d%n%n",
                    r.ctSlice, r.uniformitySlice, r.lowContrastSlice));

            sb.append("=== ESPESSURA DE CORTE ===\n");
            sb.append("Nominal (DICOM)   : ").append(d(r.sliceThicknessDicom, "mm")).append("\n");
            sb.append("Medida (superior) : ").append(d(r.sliceThicknessTop, "mm")).append("\n");
            sb.append("Medida (inferior) : ").append(d(r.sliceThicknessBottom, "mm")).append("\n\n");

            sb.append("=== EXATIDAO DO NUMERO DE CT (fatia ").append(r.ctSlice).append(") ===\n");
            sb.append("Agua          : ").append(m(r.water)).append("\n");
            sb.append("Osso          : ").append(m(r.bone)).append("\n");
            sb.append("Ar (interno)  : ").append(m(r.airIn)).append("\n");
            sb.append("Ar (fundo)    : ").append(m(r.airOut)).append("\n");
            sb.append("Polietileno   : ").append(m(r.poly)).append("\n");
            sb.append("Acrilico      : ").append(m(r.acrylic)).append("\n\n");

            sb.append("=== UNIFORMIDADE E RUIDO (fatia ").append(r.uniformitySlice).append(") ===\n");
            sb.append("Centro        : ").append(m(r.uCenter)).append("\n");
            sb.append("Topo (12h)    : ").append(m(r.uTop)).append("\n");
            sb.append("Fundo (6h)    : ").append(m(r.uBottom)).append("\n");
            sb.append("Esquerda (9h) : ").append(m(r.uLeft)).append("\n");
            sb.append("Direita (3h)  : ").append(m(r.uRight)).append("\n");
            sb.append("Maior dif. (centro-perif): ").append(d(r.maxUniformityDiff, "HU")).append("\n");
            sb.append("Ruido (SD centro)        : ").append(d(r.noise, "HU")).append("\n\n");

            sb.append("=== BAIXO CONTRASTE / CNR (fatia ").append(r.lowContrastSlice).append(") ===\n");
            sb.append("Sinal (massa) : ").append(m(r.lcSignal)).append("\n");
            sb.append("Fundo         : ").append(m(r.lcBackground)).append("\n");
            sb.append("CNR           : ").append(d(r.cnr, "")).append("\n\n");

            sb.append("=== EXATIDAO DE DISTANCIA (pinos de referencia) ===\n");
            sb.append("Distancia medida : ").append(d(r.pinDistance, "mm"));
            sb.append(String.format(java.util.Locale.US, "  (nominal %.0f mm)%n%n",
                    AcrQcAnalyser.AcrQcResult.PIN_DISTANCE_NOMINAL_MM));
        }

        sb.append("=== QUALIDADE DE IMAGEM (detalhes nos paineis TTF / NPS / NPWE) ===\n");
        if (iq != null && iq.ok) {
            sb.append("TTF e NPS publicados nos respectivos paineis.\n");
            sb.append("NPS: ").append(iq.npsSlicesUsed).append(" fatias uniformes, ROIs de 80 mm.\n");
            sb.append(String.format(java.util.Locale.US,
                    "d' (NPWE): %s | AUC: %s  [tarefa %.0f mm diam, %.0f HU]%n",
                    Double.isNaN(iq.dPrime) ? "N/A" : String.format(java.util.Locale.US, "%.4f", iq.dPrime),
                    Double.isNaN(iq.auc) ? "N/A" : String.format(java.util.Locale.US, "%.4f", iq.auc),
                    iq.taskRadiusMm * 2, iq.taskContrastHU));
        } else {
            sb.append("Pipeline de qualidade de imagem: ")
              .append(iq != null && !isBlank(iq.message) ? iq.message : "nao concluido").append("\n");
        }
        return sb.toString();
    }
}
