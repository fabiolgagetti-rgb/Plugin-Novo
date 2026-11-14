package br.rededor.fisicamedica;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;

/**
 * Aba estruturada de Controle de Qualidade do phantom ACR de TC.
 *
 * Os cálculos pesados são executados fora da EDT. Depois da análise, os
 * resultados são apresentados em cards, tabelas e um gráfico compacto de
 * uniformidade. TTF, NPS e NPWE continuam detalhados em suas abas próprias.
 * Todas as ROIs efetivamente usadas são publicadas no ROI Manager, com nome e
 * posição de fatia, sem apagar ROIs que o usuário já possua.
 */
public class AcrQcPanel {

    private static final Font VALUE_FONT = new Font("SansSerif", Font.BOLD, 15);
    private static final Font DETAIL_FONT = new Font("SansSerif", Font.PLAIN, 12);

    private static JLabel lblPatient, lblProtocol, lblKernel, lblThickness, lblKvp, lblMa,
            lblRotTime, lblPitch;
    private static JLabel statusLabel, geometryLabel, roiCountLabel;
    private static JLabel cardThickness, cardDistance, cardNoise, cardCnr, cardDPrime, cardAuc;
    private static JLabel thicknessDetail, cnrDetail, distanceDetail, imageQualityDetail;
    private static DefaultTableModel ctModel, uniformityModel;
    private static UniformityChart uniformityChart;
    private static JButton analyzeBtn, refreshBtn, copyBtn, showRoisBtn;
    private static String lastExcelLine = "";

    public static JPanel create() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel header = createHeader();
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(createSummarySection());
        content.add(Box.createVerticalStrut(8));
        content.add(createCtSection());
        content.add(Box.createVerticalStrut(8));
        content.add(createUniformitySection());
        content.add(Box.createVerticalStrut(8));
        content.add(createThicknessAndCnrSection());
        content.add(Box.createVerticalStrut(8));
        content.add(createDistanceSection());
        content.add(Box.createVerticalStrut(8));
        content.add(createImageQualitySection());
        content.add(Box.createVerticalGlue());

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        root.add(header, BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);
        root.setPreferredSize(new Dimension(900, 650));

        refreshBtn.addActionListener(e -> refreshDicomInfo());
        analyzeBtn.addActionListener(e -> runAnalysis());
        copyBtn.addActionListener(e -> copyExcelLine());
        showRoisBtn.addActionListener(e -> showRoiManager());

        resetResults();
        refreshDicomInfo();
        return root;
    }

    private static JPanel createHeader() {
        JPanel wrapper = new JPanel(new BorderLayout(4, 4));
        JPanel header = new JPanel(new GridBagLayout());
        header.setBorder(BorderFactory.createTitledBorder("Informações da aquisição (DICOM)"));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 6, 3, 6);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;

        lblPatient = valueLabel();
        lblProtocol = valueLabel();
        lblKernel = valueLabel();
        lblThickness = valueLabel();
        lblKvp = valueLabel();
        lblMa = valueLabel();
        lblRotTime = valueLabel();
        lblPitch = valueLabel();

        int row = 0;
        row = addHeaderRow(header, g, row, "Paciente / série:", lblPatient, "Protocolo:", lblProtocol);
        row = addHeaderRow(header, g, row, "Kernel:", lblKernel, "Espessura:", lblThickness);
        row = addHeaderRow(header, g, row, "kVp:", lblKvp, "mA:", lblMa);
        addHeaderRow(header, g, row, "Tempo de rotação:", lblRotTime, "Pitch:", lblPitch);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        refreshBtn = new JButton("Atualizar informações");
        analyzeBtn = new JButton("Analisar");
        analyzeBtn.setFont(analyzeBtn.getFont().deriveFont(Font.BOLD));
        copyBtn = new JButton("Copiar linha Excel");
        showRoisBtn = new JButton("Mostrar ROI Manager");
        actions.add(refreshBtn);
        actions.add(analyzeBtn);
        actions.add(copyBtn);
        actions.add(showRoisBtn);

        statusLabel = new JLabel("Selecione uma série ACR e clique em Analisar.");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 8, 2, 4));
        roiCountLabel = new JLabel("ROIs: -", SwingConstants.RIGHT);
        roiCountLabel.setBorder(BorderFactory.createEmptyBorder(0, 4, 2, 8));

        JPanel statusRow = new JPanel(new BorderLayout());
        statusRow.add(statusLabel, BorderLayout.CENTER);
        statusRow.add(roiCountLabel, BorderLayout.EAST);

        wrapper.add(header, BorderLayout.NORTH);
        wrapper.add(actions, BorderLayout.CENTER);
        wrapper.add(statusRow, BorderLayout.SOUTH);
        return wrapper;
    }

    private static JPanel createSummarySection() {
        JPanel section = section("Resumo");
        section.setLayout(new BorderLayout(6, 6));
        geometryLabel = new JLabel("Fatias analisadas: -");
        geometryLabel.setBorder(BorderFactory.createEmptyBorder(0, 4, 2, 4));

        JPanel cards = new JPanel(new GridLayout(2, 3, 8, 8));
        cardThickness = addCard(cards, "Espessura medida");
        cardDistance = addCard(cards, "Distância dos pinos");
        cardNoise = addCard(cards, "Ruído central");
        cardCnr = addCard(cards, "CNR baixo contraste");
        cardDPrime = addCard(cards, "Índice d′ (NPWE)");
        cardAuc = addCard(cards, "AUC");

        section.add(geometryLabel, BorderLayout.NORTH);
        section.add(cards, BorderLayout.CENTER);
        return section;
    }

    private static JLabel addCard(JPanel parent, String title) {
        JPanel card = new JPanel(new BorderLayout(2, 2));
        card.setBackground(new Color(248, 249, 251));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(205, 210, 218)),
                BorderFactory.createEmptyBorder(7, 9, 7, 9)));
        JLabel caption = new JLabel(title);
        caption.setFont(caption.getFont().deriveFont(Font.PLAIN, 11f));
        JLabel value = new JLabel("-");
        value.setFont(VALUE_FONT);
        card.add(caption, BorderLayout.NORTH);
        card.add(value, BorderLayout.CENTER);
        parent.add(card);
        return value;
    }

    private static JPanel createCtSection() {
        JPanel section = section("Exatidão do número de CT");
        section.setLayout(new BorderLayout());
        ctModel = tableModel("Material", "Média (HU)", "SD (HU)", "Fatia");
        JTable table = styledTable(ctModel);
        table.setPreferredScrollableViewportSize(new Dimension(760, 112));
        section.add(new JScrollPane(table), BorderLayout.CENTER);
        return section;
    }

    private static JPanel createUniformitySection() {
        JPanel section = section("Uniformidade e ruído");
        section.setLayout(new BorderLayout(8, 4));
        uniformityModel = tableModel("Posição", "Média (HU)", "SD (HU)", "Δ centro (HU)", "Fatia");
        JTable table = styledTable(uniformityModel);
        table.setPreferredScrollableViewportSize(new Dimension(555, 105));
        uniformityChart = new UniformityChart();
        uniformityChart.setPreferredSize(new Dimension(245, 155));
        section.add(new JScrollPane(table), BorderLayout.CENTER);
        section.add(uniformityChart, BorderLayout.EAST);
        return section;
    }

    private static JPanel createThicknessAndCnrSection() {
        JPanel row = new JPanel(new GridLayout(1, 2, 8, 0));
        row.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 190));
        JPanel thickness = section("Espessura de corte");
        thickness.setLayout(new BorderLayout());
        thicknessDetail = detailLabel();
        thickness.add(thicknessDetail, BorderLayout.CENTER);

        JPanel cnr = section("Baixo contraste / CNR");
        cnr.setLayout(new BorderLayout());
        cnrDetail = detailLabel();
        cnr.add(cnrDetail, BorderLayout.CENTER);

        row.add(thickness);
        row.add(cnr);
        return row;
    }

    private static JPanel createDistanceSection() {
        JPanel section = section("Exatidão da distância entre os pinos");
        section.setLayout(new BorderLayout());
        distanceDetail = detailLabel();
        section.add(distanceDetail, BorderLayout.CENTER);
        return section;
    }

    private static JPanel createImageQualitySection() {
        JPanel section = section("Qualidade de imagem (detalhes nas abas TTF, NPS e NPWE)");
        section.setLayout(new BorderLayout());
        imageQualityDetail = detailLabel();
        section.add(imageQualityDetail, BorderLayout.CENTER);
        return section;
    }

    private static JPanel section(String title) {
        JPanel p = new JPanel();
        p.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 340));
        p.setBorder(BorderFactory.createTitledBorder(title));
        return p;
    }

    private static JLabel detailLabel() {
        JLabel label = new JLabel("-");
        label.setFont(DETAIL_FONT);
        label.setVerticalAlignment(SwingConstants.TOP);
        label.setBorder(BorderFactory.createEmptyBorder(5, 8, 7, 8));
        return label;
    }

    private static DefaultTableModel tableModel(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    private static JTable styledTable(DefaultTableModel model) {
        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.setRowHeight(21);
        table.getTableHeader().setReorderingAllowed(false);
        return table;
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

    private static void refreshDicomInfo() {
        SeriesData sd = ImageOpener.getActiveSeriesData();
        if (sd == null || sd.getFiles().isEmpty()) {
            setInfo("(nenhuma série ativa)", "-", "-", "-", "-", "-", "-", "-");
            return;
        }
        File f = sd.getFiles().get(0);
        String meta = sd.getMetadata(f);
        String patient = orDash(tag(meta, "0010,0010"));
        String series = orDash(sd.getSeriesDescription());
        String protocol = tag(meta, "0018,1030");
        String kernel = tag(meta, "0018,1210");
        String thick = tag(meta, "0018,0050");
        String kvp = tag(meta, "0018,0060");
        String ma = tag(meta, "0018,1151");
        if (isBlank(ma)) ma = tag(meta, "0018,1152");
        String rot = tag(meta, "0018,9305");
        if (isBlank(rot)) rot = tag(meta, "0018,1150");
        String pitch = tag(meta, "0018,9311");

        setInfo(patient + " / " + series,
                orDash(protocol), orDash(kernel),
                isBlank(thick) ? "-" : thick + " mm",
                isBlank(kvp) ? "-" : kvp + " kV", orDash(ma),
                isBlank(rot) ? "-" : rot, orDash(pitch));
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

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String orDash(String s) {
        return isBlank(s) ? "-" : s.trim();
    }

    private static void runAnalysis() {
        final SeriesData series = ImageOpener.getActiveSeriesData();
        final ImagePlus image = ImageOpener.getActiveImagePlus();
        if (series == null || series.getFiles().isEmpty()) {
            JOptionPane.showMessageDialog(null,
                    "Nenhuma série ativa. Abra o phantom ACR no navegador de arquivos.",
                    "CQ ACR", JOptionPane.WARNING_MESSAGE);
            return;
        }

        setBusy(true);
        status("Analisando módulos, testes de CQ, TTF, NPS e d′...", new Color(35, 80, 145));
        final double[] eyeFilter = DetectabilityIndexPanel.getEyeFilterParams();

        new SwingWorker<AnalysisBundle, Void>() {
            @Override
            protected AnalysisBundle doInBackground() {
                AcrQcAnalyser.AcrQcResult qc = AcrQcAnalyser.analyze(series);
                AcrQcAnalyser.ImageQualityResult iq = AcrQcAnalyser.runImageQualityPipeline(series, eyeFilter);
                return new AnalysisBundle(qc, iq);
            }

            @Override
            protected void done() {
                try {
                    AnalysisBundle result = get();
                    updateResults(result.qc, result.iq);
                    lastExcelLine = result.qc != null && result.qc.found ? result.qc.excelLine : "";
                    copyBtn.setEnabled(!lastExcelLine.isEmpty());

                    if (result.qc == null || !result.qc.found) {
                        roiCountLabel.setText("ROIs não publicadas");
                        status("Falha na análise de CQ: "
                                + (result.qc == null ? "resultado ausente" : result.qc.message),
                                new Color(170, 35, 35));
                        return;
                    }

                    boolean sameSeries = ImageOpener.getActiveSeriesData() == series;
                    boolean sameImage = image != null && ImageOpener.getActiveImagePlus() == image;
                    if (sameSeries && sameImage) {
                        publishImageQuality(result.iq);
                        int roiCount = publishRois(image, result.qc, result.iq);
                        roiCountLabel.setText("ROIs publicadas: " + roiCount);
                        status("Análise concluída. Resultados e ROIs associados à série ativa.", new Color(25, 110, 55));
                    } else {
                        roiCountLabel.setText("ROIs não publicadas");
                        status("Análise concluída, mas a série ativa mudou. ROIs e curvas não foram publicadas.",
                                new Color(175, 95, 15));
                    }
                } catch (Exception ex) {
                    status("Erro durante a análise: " + safeMessage(ex), new Color(170, 35, 35));
                    IJ.handleException(ex);
                } finally {
                    setBusy(false);
                }
            }
        }.execute();
    }

    private static int publishRois(ImagePlus image, AcrQcAnalyser.AcrQcResult qc,
                                   AcrQcAnalyser.ImageQualityResult iq) {
        List<AcrQcAnalyser.RoiRecord> records = new ArrayList<>();
        if (qc != null) records.addAll(qc.rois);
        if (iq != null) records.addAll(iq.rois);
        if (records.isEmpty()) return 0;

        RoiManager manager = RoiManager.getInstance();
        if (manager == null) manager = new RoiManager();
        String runId = new SimpleDateFormat("HHmmss", Locale.US).format(new Date());
        int added = 0;
        for (AcrQcAnalyser.RoiRecord record : records) {
            if (record == null || record.roi == null || record.slice < 1) continue;
            Roi roi = (Roi) record.roi.clone();
            roi.setName(String.format(Locale.US, "ACR %s | %s | z=%03d",
                    runId, record.name, record.slice));
            roi.setStrokeColor(colorFor(record.test));
            roi.setStrokeWidth(record.roi instanceof ij.gui.Line ? 2.0 : 1.0);
            roi.setPosition(record.slice);
            roi.setImage(image);
            manager.add(image, roi, -1);
            added++;
        }
        manager.setVisible(true);
        manager.runCommand("Show All");
        return added;
    }

    private static Color colorFor(String test) {
        if ("CT".equals(test)) return new Color(230, 125, 30);
        if ("Uniformidade".equals(test)) return new Color(35, 110, 210);
        if ("CNR".equals(test)) return new Color(35, 150, 75);
        if ("Espessura".equals(test)) return new Color(180, 65, 180);
        if ("Distancia".equals(test)) return new Color(235, 190, 30);
        if ("NPS".equals(test)) return new Color(20, 165, 175);
        if ("TTF".equals(test)) return new Color(205, 55, 55);
        return Color.YELLOW;
    }

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

    private static void updateResults(AcrQcAnalyser.AcrQcResult r,
                                      AcrQcAnalyser.ImageQualityResult iq) {
        resetResults();
        if (r == null || !r.found) {
            status("Falha na análise de CQ: " + (r == null ? "resultado ausente" : r.message),
                    new Color(170, 35, 35));
            return;
        }

        geometryLabel.setText(String.format(Locale.US,
                "Phantom: fatias %d–%d  |  CT: %d  |  Uniformidade: %d  |  Baixo contraste: %d  |  Pinos: %d",
                r.phantomStart, r.phantomEnd, r.ctSlice, r.uniformitySlice,
                r.lowContrastSlice, r.pinSlice));

        cardThickness.setText(d(mean(r.sliceThicknessTop, r.sliceThicknessBottom), "mm"));
        cardDistance.setText(d(r.pinDistance, "mm"));
        cardNoise.setText(d(r.noise, "HU"));
        cardCnr.setText(d(r.cnr, ""));
        cardDPrime.setText(iq != null ? d(iq.dPrime, "") : "N/A");
        cardAuc.setText(iq != null ? d(iq.auc, "") : "N/A");

        addCtRow(r.airIn, r.ctSlice);
        addCtRow(r.poly, r.ctSlice);
        addCtRow(r.water, r.ctSlice);
        addCtRow(r.acrylic, r.ctSlice);
        addCtRow(r.bone, r.ctSlice);
        addCtRow(r.airOut, r.ctSlice);

        addUniformityRow(r.uCenter, r.uCenter, r.uniformitySlice);
        addUniformityRow(r.uTop, r.uCenter, r.uniformitySlice);
        addUniformityRow(r.uRight, r.uCenter, r.uniformitySlice);
        addUniformityRow(r.uBottom, r.uCenter, r.uniformitySlice);
        addUniformityRow(r.uLeft, r.uCenter, r.uniformitySlice);
        uniformityChart.setMeasurements(r.uCenter, r.uTop, r.uRight, r.uBottom, r.uLeft);

        thicknessDetail.setText(html(
                line("Nominal (DICOM)", d(r.sliceThicknessDicom, "mm")),
                line("Superior", d(r.sliceThicknessTop, "mm")),
                line("Inferior", d(r.sliceThicknessBottom, "mm")),
                optionalLine("Observação", r.sliceThicknessNote)));

        cnrDetail.setText(html(
                line("Sinal", measurement(r.lcSignal)),
                line("Fundo", measurement(r.lcBackground)),
                line("CNR", d(r.cnr, "")),
                line("Fatia", String.valueOf(r.lowContrastSlice))));

        String inversion = r.pinPhantomInverted
                ? "<font color='#b03030'><b>Verificar orientação do phantom</b></font>"
                : "Orientação nominal";
        distanceDetail.setText(html(
                line("Distância escolhida", d(r.pinDistance, "mm")),
                line("Par nominal", d(r.pinDistanceH, "mm")),
                line("Par espelhado", d(r.pinDistanceV, "mm")),
                line("Orientação", inversion),
                optionalLine("Detalhes", r.pinNote)));

        if (iq != null && iq.ok) {
            double varianceError = percentDifference(iq.npsVariance, iq.npsIntegrated);
            imageQualityDetail.setText(html(
                    line("TTF", iq.ttfSlicesUsed + " fatias; inserto selecionado "
                            + (iq.lowestContrastInsertId < 0 ? "N/A" : iq.lowestContrastInsertId + 1)),
                    line("NPS", String.format(Locale.US,
                            "%d fatias / %d ROIs (%.0f mm; raio %.0f mm)",
                            iq.npsSlicesUsed, iq.npsRoisUsed,
                            iq.npsRoiSizeMm, iq.npsDistributionRadiusMm)),
                    line("Variância / NPS integrada", String.format(Locale.US, "%.3f / %.3f (dif. %.2f%%)",
                            iq.npsVariance, iq.npsIntegrated, varianceError)),
                    line("Frequência pico / média", String.format(Locale.US, "%.3f / %.3f ciclos/mm",
                            iq.npsPeakFreq, iq.npsMeanFreq)),
                    line("NPWE", String.format(Locale.US, "d′ %.4f | AUC %.4f | tarefa %.0f mm, %.0f HU",
                            iq.dPrime, iq.auc, iq.taskRadiusMm * 2, iq.taskContrastHU))));
        } else {
            imageQualityDetail.setText("Pipeline de TTF/NPS/NPWE não concluído.");
        }
    }

    private static void addCtRow(AcrQcAnalyser.Measure m, int slice) {
        ctModel.addRow(new Object[]{m == null ? "-" : m.label,
                formatMeasureValue(m, true), formatMeasureValue(m, false), slice});
    }

    private static void addUniformityRow(AcrQcAnalyser.Measure m,
                                         AcrQcAnalyser.Measure center, int slice) {
        String diff = m != null && m.valid && center != null && center.valid
                ? String.format(Locale.US, "%.2f", m.mean - center.mean) : "N/A";
        uniformityModel.addRow(new Object[]{m == null ? "-" : m.label,
                formatMeasureValue(m, true), formatMeasureValue(m, false), diff, slice});
    }

    private static String formatMeasureValue(AcrQcAnalyser.Measure m, boolean mean) {
        if (m == null || !m.valid) return "N/A";
        return String.format(Locale.US, "%.2f", mean ? m.mean : m.sd);
    }

    private static String measurement(AcrQcAnalyser.Measure m) {
        if (m == null || !m.valid) return "N/A";
        return String.format(Locale.US, "%.2f ± %.2f HU", m.mean, m.sd);
    }

    private static double mean(double a, double b) {
        if (Double.isNaN(a) || Double.isNaN(b)) return Double.NaN;
        return (a + b) / 2.0;
    }

    private static double percentDifference(double a, double b) {
        if (Double.isNaN(a) || Double.isNaN(b) || a == 0) return Double.NaN;
        return Math.abs(a - b) * 100.0 / Math.abs(a);
    }

    private static String d(double value, String unit) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return "N/A";
        return String.format(Locale.US, "%.2f%s", value, unit.isEmpty() ? "" : " " + unit);
    }

    private static String html(String... rows) {
        StringBuilder sb = new StringBuilder("<html><table cellpadding='2'>");
        for (String row : rows) if (row != null && !row.isEmpty()) sb.append(row);
        return sb.append("</table></html>").toString();
    }

    private static String line(String key, String value) {
        return "<tr><td><b>" + key + ":</b></td><td>" + value + "</td></tr>";
    }

    private static String optionalLine(String key, String value) {
        return isBlank(value) ? "" : line(key, value);
    }

    private static void resetResults() {
        if (ctModel != null) ctModel.setRowCount(0);
        if (uniformityModel != null) uniformityModel.setRowCount(0);
        if (uniformityChart != null) uniformityChart.clear();
        if (geometryLabel != null) geometryLabel.setText("Fatias analisadas: -");
        setCard(cardThickness, "-");
        setCard(cardDistance, "-");
        setCard(cardNoise, "-");
        setCard(cardCnr, "-");
        setCard(cardDPrime, "-");
        setCard(cardAuc, "-");
        if (thicknessDetail != null) thicknessDetail.setText("-");
        if (cnrDetail != null) cnrDetail.setText("-");
        if (distanceDetail != null) distanceDetail.setText("-");
        if (imageQualityDetail != null) imageQualityDetail.setText("-");
        lastExcelLine = "";
        if (copyBtn != null) copyBtn.setEnabled(false);
    }

    private static void setCard(JLabel label, String value) {
        if (label != null) label.setText(value);
    }

    private static void setBusy(boolean busy) {
        analyzeBtn.setEnabled(!busy);
        refreshBtn.setEnabled(!busy);
    }

    private static void status(String text, Color color) {
        statusLabel.setText(text);
        statusLabel.setForeground(color);
    }

    private static void copyExcelLine() {
        if (lastExcelLine.isEmpty()) return;
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(lastExcelLine), null);
        status("Linha copiada para a área de transferência.", new Color(25, 110, 55));
    }

    private static void showRoiManager() {
        RoiManager manager = RoiManager.getInstance();
        if (manager == null) manager = new RoiManager();
        manager.setVisible(true);
    }

    private static String safeMessage(Exception ex) {
        Throwable cause = ex.getCause() == null ? ex : ex.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static class AnalysisBundle {
        final AcrQcAnalyser.AcrQcResult qc;
        final AcrQcAnalyser.ImageQualityResult iq;

        AnalysisBundle(AcrQcAnalyser.AcrQcResult qc, AcrQcAnalyser.ImageQualityResult iq) {
            this.qc = qc;
            this.iq = iq;
        }
    }

    /** Gráfico simples das diferenças periféricas em relação ao centro. */
    private static class UniformityChart extends JPanel {
        private static final long serialVersionUID = 1L;
        private final String[] labels = {"12h", "3h", "6h", "9h"};
        private double[] values;

        void setMeasurements(AcrQcAnalyser.Measure center, AcrQcAnalyser.Measure top,
                             AcrQcAnalyser.Measure right, AcrQcAnalyser.Measure bottom,
                             AcrQcAnalyser.Measure left) {
            if (center == null || !center.valid) {
                clear();
                return;
            }
            AcrQcAnalyser.Measure[] peripheral = {top, right, bottom, left};
            values = new double[4];
            for (int i = 0; i < values.length; i++) {
                values[i] = peripheral[i] != null && peripheral[i].valid
                        ? peripheral[i].mean - center.mean : Double.NaN;
            }
            repaint();
        }

        void clear() {
            values = null;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            int left = 30, right = 8, top = 20, bottom = 28;
            int chartW = Math.max(1, w - left - right);
            int chartH = Math.max(1, h - top - bottom);
            int zeroY = top + chartH / 2;

            g.setColor(new Color(85, 90, 100));
            g.setStroke(new BasicStroke(1f));
            g.drawLine(left, zeroY, left + chartW, zeroY);
            g.drawString("Δ HU", 5, 14);

            if (values == null) {
                g.setColor(Color.GRAY);
                g.drawString("Sem dados", left + 50, zeroY);
                g.dispose();
                return;
            }

            double maxAbs = 1.0;
            for (double v : values) if (!Double.isNaN(v)) maxAbs = Math.max(maxAbs, Math.abs(v));
            int slot = chartW / values.length;
            int barW = Math.max(10, slot / 2);
            for (int i = 0; i < values.length; i++) {
                double v = values[i];
                int x = left + i * slot + (slot - barW) / 2;
                if (!Double.isNaN(v)) {
                    int barH = (int) Math.round(Math.abs(v) / maxAbs * (chartH / 2.0 - 12));
                    int y = v >= 0 ? zeroY - barH : zeroY;
                    g.setColor(v >= 0 ? new Color(55, 125, 205) : new Color(75, 165, 135));
                    g.fillRect(x, y, barW, barH);
                    g.setColor(new Color(60, 65, 75));
                    g.drawString(String.format(Locale.US, "%.1f", v), x - 2, v >= 0 ? y - 3 : y + barH + 12);
                }
                g.setColor(new Color(60, 65, 75));
                g.drawString(labels[i], x, h - 7);
            }
            g.dispose();
        }
    }
}
