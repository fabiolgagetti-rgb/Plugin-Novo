package br.rededor.fisicamedica;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.text.DecimalFormat;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

import org.apache.commons.math3.special.Erf;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.GenericDialog;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

public class DetectabilityIndexPanel {

    private static JComboBox<String> TaskSelector;
    private static JTextField taskContrastField;
    private static JTextField taskRadiusField;
    
    private static JTextField viewDistanceField;
    private static JTextField displaySizeField;
    private static JTextField fovField;
    
    private static JTextField dPrimeResultField;
    private static JTextField AucResultField;
    
    private static JButton btnShowImages;
    private static JButton btnGeneratePhantom;
    private static JButton btnAutoAcr; // Novo botão
    
    private static double[][] lastNps2D = null;
    private static double[] lastNpsFreq = null;
    private static double[] lastNpsData = null;
    private static double[] lastTtfFreq = null; 
    private static double[] lastTtfData = null; 
    private static double lastTaskContrast = 0;
    private static double lastTaskRadius = 0;
    
    private static final DecimalFormat DF = new DecimalFormat("0.####");

    public static JPanel create() {

        JPanel root = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 8, 8, 8);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JPanel params = new JPanel(new GridBagLayout());
        params.setBorder(BorderFactory.createTitledBorder("d' Parameters"));
        GridBagConstraints pGBC = new GridBagConstraints();
        pGBC.insets = new Insets(5, 5, 5, 5);
        pGBC.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;

        pGBC.gridx = 0; pGBC.gridy = row;
        params.add(new JLabel("Target Insert:"), pGBC);
        
        TaskSelector = new JComboBox<>();
        pGBC.gridx = 1; pGBC.gridy = row++;
        params.add(TaskSelector, pGBC);

        JButton calcBtn = new JButton("Calculate d'");
        pGBC.gridx = 0; pGBC.gridy = row++; pGBC.gridwidth = 2;
        params.add(calcBtn, pGBC);

        pGBC.gridwidth = 1;
        pGBC.gridx = 0; pGBC.gridy = row;
        params.add(new JLabel("Task Contrast (HU):"), pGBC);
        taskContrastField = new JTextField("7", 8);
        pGBC.gridx = 1; pGBC.gridy = row;
        params.add(taskContrastField, pGBC);
        row++;
        
        pGBC.gridx = 0; pGBC.gridy = row;
        params.add(new JLabel("Task Radius (mm):"), pGBC);
        taskRadiusField = new JTextField("6", 8);
        pGBC.gridx = 1; pGBC.gridy = row;
        params.add(taskRadiusField, pGBC);
        row++;
        
        pGBC.gridx = 0; pGBC.gridy = row;
        params.add(new JLabel("Viewing Distance (mm):"), pGBC);
        viewDistanceField = new JTextField("500", 8);
        pGBC.gridx = 1; pGBC.gridy = row;
        params.add(viewDistanceField, pGBC);
        row++;
        
        pGBC.gridx = 0; pGBC.gridy = row;
        params.add(new JLabel("Display Size (mm):"), pGBC);
        displaySizeField = new JTextField("305", 8);
        pGBC.gridx = 1; pGBC.gridy = row;
        params.add(displaySizeField, pGBC);
        row++;

        pGBC.gridx = 0; pGBC.gridy = row;
        params.add(new JLabel("Display FOV (mm):"), pGBC);
        fovField = new JTextField("250", 8);
        pGBC.gridx = 1; pGBC.gridy = row;
        params.add(fovField, pGBC);
        row++;
        
        // --- NOVO BOTÃO PARA O FLUXO AUTOMÁTICO ---
        btnAutoAcr = new JButton("Auto ACR (Full Pipeline)");
        pGBC.gridx = 0; pGBC.gridy = row++; pGBC.gridwidth = 2;
        params.add(btnAutoAcr, pGBC);
        
     // --- NOVO BOTÃO DE BATCH ---
        JButton btnBatchAcr = new JButton("Batch ACR Optimization");
        pGBC.gridx = 0; pGBC.gridy = row++; pGBC.gridwidth = 2;
        params.add(btnBatchAcr, pGBC);
        
        // ... mais para baixo, na seção de ActionListeners ...
        btnBatchAcr.addActionListener(e -> runBatchAcrRoutine());
        
        updateDataSources();        
        
        JPanel results = new JPanel(new GridBagLayout());
        results.setBorder(BorderFactory.createTitledBorder("Result"));
        GridBagConstraints rGBC = new GridBagConstraints();
        rGBC.insets = new Insets(5, 5, 5, 5);
        
        rGBC.gridx = 0; rGBC.gridy = 0;
        results.add(new JLabel("Detectability Index (d'):"), rGBC);
        dPrimeResultField = new JTextField("-", 12);
        dPrimeResultField.setEditable(false);
        rGBC.gridx = 1; rGBC.gridy = 0;
        results.add(dPrimeResultField, rGBC);
        
        rGBC.gridx = 0; rGBC.gridy = 1;
        results.add(new JLabel("AUC:"), rGBC);
        AucResultField = new JTextField("-", 12);
        AucResultField.setEditable(false);
        rGBC.gridx = 1; rGBC.gridy = 1;
        results.add(AucResultField, rGBC);

        btnShowImages = new JButton("Show Detectability Task");
        btnShowImages.setEnabled(false); 
        rGBC.gridx = 0; rGBC.gridy = 2; rGBC.gridwidth = 2;
        rGBC.fill = GridBagConstraints.HORIZONTAL;
        results.add(btnShowImages, rGBC);
        
        btnGeneratePhantom = new JButton("Generate Virtual Anthropomorphic Phantom");
        btnGeneratePhantom.setEnabled(false); 
        rGBC.gridy = 3; 
        results.add(btnGeneratePhantom, rGBC);

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 1;
        params.setPreferredSize(new Dimension(350, 320));
        root.add(params, gbc);
        
        gbc.gridx = 0; gbc.gridy = 1;
        results.setPreferredSize(new Dimension(350, 160)); 
        root.add(results, gbc);

        TaskSelector.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                updateDataSources();
            }
            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}
            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {}
        });

        calcBtn.addActionListener(e -> calculate(root));
        
        // --- AÇÃO DO NOVO BOTÃO ACR ---
        btnAutoAcr.addActionListener(e -> runAutoAcrRoutine());
        
        btnShowImages.addActionListener(e -> {
            if (lastNps2D != null && lastTtfFreq != null && lastTtfData != null) {
               Calibration realCal = TtfPanel.getLastCal();
               double realPixelSize = (realCal != null && realCal.pixelWidth > 0) ? realCal.pixelWidth : 0.5;
               exibirStackNoImageJ(realPixelSize, 512, lastNps2D, lastTtfFreq, lastTtfData, lastTaskContrast, lastTaskRadius);
            }
        });
        
       btnGeneratePhantom.addActionListener(e -> {
            if (lastNps2D != null && lastTtfFreq != null && lastTtfData != null) {
                gerarPhantomVirtual(lastTtfFreq, lastTtfData, lastNps2D);
            } else {
                JOptionPane.showMessageDialog(root, "Please calculate d' first to acquire NPS and TTF data.");
            }
        });

        return root;
    }

    /**
     * Rotina que exibe o diálogo para pegar parâmetros e aciona a classe AcrAutoDetectability
     */
    private static void runAutoAcrRoutine() {
        SeriesData sd = ImageOpener.getActiveSeriesData();
        if (sd == null || sd.getFiles().isEmpty()) {
            IJ.error("Nenhuma série ativa. Abra as imagens do ACR Phantom no File Browser primeiro.");
            return;
        }

        // Criar diálogo do ImageJ para os parâmetros
        GenericDialog gd = new GenericDialog("Automated ACR Phantom d' Parameters");
        gd.addMessage("Parâmetros do Phantom e TTF/NPS:");
        
        // --- ALTERAÇÃO AQUI: Menu suspenso de materiais em vez de índice numérico ---
        String[] materials = {"Bone", "Air", "Acrylic", "Polyethylene"};
        gd.addChoice("Target Material (TTF):", materials, "Bone");
        
        gd.addNumericField("Number of ROIs (NPS):", 12, 0);
        gd.addNumericField("ROI Size (px):", 64, 0);
        gd.addNumericField("NPS Distribution Radius (mm):", 40.0, 1);
        
        gd.addMessage("Parâmetros da Tarefa Diagnóstica:");
        gd.addNumericField("Task Radius (mm):", Double.parseDouble(taskRadiusField.getText().trim()), 1);
        gd.addNumericField("Task Contrast (HU):", Double.parseDouble(taskContrastField.getText().trim()), 1);
        gd.addNumericField("Viewing Distance (mm):", Double.parseDouble(viewDistanceField.getText().trim()), 1);
        gd.addNumericField("Display Size (mm):", Double.parseDouble(displaySizeField.getText().trim()), 1);
        gd.addNumericField("Display FOV (mm):", Double.parseDouble(fovField.getText().trim()), 1);
        
        gd.showDialog();
        if (gd.wasCanceled()) {
            return;
        }

        // --- ALTERAÇÃO AQUI: Extrair o texto selecionado em vez de número ---
        String targetMaterial = gd.getNextChoice();
        
        int numRois     = (int) gd.getNextNumber();
        int roiSizePx   = (int) gd.getNextNumber();
        double distRad  = gd.getNextNumber();
        double tRad     = gd.getNextNumber();
        double tCont    = gd.getNextNumber();
        double vDist    = gd.getNextNumber();
        double dSize    = gd.getNextNumber();
        double fov      = gd.getNextNumber();

        // Chamar o motor automático (passando a String targetMaterial)
        AcrAutoDetectability.AutoDetectResult res = AcrAutoDetectability.runAutoACR(
                targetMaterial, numRois, roiSizePx, distRad, tRad, tCont, vDist, dSize, fov);

        // Exibir resultados no Log e na própria Interface
        if (res != null) {
            IJ.log("====================================");
            IJ.log("Resultados Automáticos - Phantom ACR");
            IJ.log("Inserto Utilizado: " + targetMaterial);
            IJ.log("Índice de Detectabilidade (d'): " + DF.format(res.dPrime));
            IJ.log("Área sob a curva (AUC): " + DF.format(res.auc));
            IJ.log("Módulo 1 (TTF) extraído: fatias " + res.mod1Start + " a " + res.mod1End);
            IJ.log("Módulo 3 (NPS) extraído: fatias " + res.mod3Start + " a " + res.mod3End + " (" + res.validNpsSlices + " válidas)");
            IJ.log("====================================");

            dPrimeResultField.setText(DF.format(res.dPrime));
            AucResultField.setText(DF.format(res.auc));
            
            taskRadiusField.setText(String.valueOf(tRad));
            taskContrastField.setText(String.valueOf(tCont));
            viewDistanceField.setText(String.valueOf(vDist));
            displaySizeField.setText(String.valueOf(dSize));
            fovField.setText(String.valueOf(fov));
            
            IJ.showMessage("ACR Automático", "Cálculo concluído com sucesso!\nVerifique a janela de Log do ImageJ.");
        }
    }

    private static void updateDataSources() {
        List<Integer> insertIDs = TtfPanel.getLastInsertIDs();

        if (insertIDs == null || insertIDs.isEmpty()) {
            TaskSelector.setModel(new DefaultComboBoxModel<>());
            return;
        }

        Object currentSelection = TaskSelector.getSelectedItem();
        
        DefaultComboBoxModel<String> newModel = new DefaultComboBoxModel<>();
        for (Integer id : insertIDs) {
            newModel.addElement("TTF Task " + (id + 1));
        }
        TaskSelector.setModel(newModel);
        
        if (currentSelection != null && newModel.getIndexOf(currentSelection) >= 0) {
            TaskSelector.setSelectedItem(currentSelection);
        }
    }
    
    private static void calculate(JPanel parent) {
        btnShowImages.setEnabled(false);
        btnGeneratePhantom.setEnabled(false);
        
        try {
            double[] npsFreq = NPSpanel.getLastRadialFreq();
            double[] npsData = NPSpanel.getLastRadialNPS();
            double[][] nps2D = NPSpanel.getLastNPS2D();
            
            if (npsFreq == null || npsData == null || nps2D == null) {
                JOptionPane.showMessageDialog(parent, "Error: No NPS calculated.", "Erro", JOptionPane.ERROR_MESSAGE);
                return;
            }

            TtfCalc.mtfResult ttfRes = TtfPanel.getLastTtfResult();
            String selectedStr = (String) TaskSelector.getSelectedItem();
            
            if (selectedStr == null || ttfRes == null || ttfRes.ttfs == null) {
                 JOptionPane.showMessageDialog(parent, "No valid insert selected or no TTF data available.", "Erro", JOptionPane.ERROR_MESSAGE);
                return;
            }

            int selectedID = Integer.parseInt(selectedStr.replace("TTF Task ", "")) - 1;

            if (!ttfRes.ttfs.containsKey(selectedID)) {
                 JOptionPane.showMessageDialog(parent, "No valid TTF data for this insert.", "Erro", JOptionPane.ERROR_MESSAGE);
                 return;
            }

            double[] ttfData = ttfRes.ttfs.get(selectedID);
            double[] ttfFreq = ttfRes.freqs.get(selectedID);
            
            if (ttfData == null || ttfFreq == null) {
                JOptionPane.showMessageDialog(parent, "Nenhum dado de TTF encontrado para este inserto.", "Erro", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            double taskContrast = Double.parseDouble(taskContrastField.getText().trim());
            double taskRadius = Double.parseDouble(taskRadiusField.getText().trim());
            double viewDistance = Double.parseDouble(viewDistanceField.getText().trim());
            double displaySize = Double.parseDouble(displaySizeField.getText().trim());
            double fov = Double.parseDouble(fovField.getText().trim());

            DetectabilityIndexCalculator calculator = new DetectabilityIndexCalculator(
                ttfData, ttfFreq, 
                npsData, npsFreq, 
                taskRadius, 
                taskContrast, 
                viewDistance, 
                displaySize,
                fov
            );
            
            double dPrime = calculator.compute();

            dPrimeResultField.setText(DF.format(dPrime));
            double auc = 0.5 + 0.5 * Erf.erf(dPrime / 2.0);
            AucResultField.setText(DF.format(auc));

            lastNps2D = nps2D;
            lastNpsFreq = npsFreq;
            lastNpsData = npsData;
            lastTtfFreq = ttfFreq;
            lastTtfData = ttfData;
            lastTaskContrast = taskContrast;
            lastTaskRadius = taskRadius;
            
            btnShowImages.setEnabled(true);
            btnGeneratePhantom.setEnabled(true);

        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(parent, "Error in numerical input", "Error", JOptionPane.ERROR_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent, "Error: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            ex.printStackTrace();
        }
    }
    
    private static double[][] expandNpsToSize(double[][] smallNps, int targetSize) {
        int h = smallNps.length;
        int w = smallNps[0].length;
        FloatProcessor fp = new FloatProcessor(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                fp.setf(x, y, (float) smallNps[y][x]);
            }
        }
        fp.setInterpolationMethod(ImageProcessor.BICUBIC);
        FloatProcessor resized = (FloatProcessor) fp.resize(targetSize, targetSize);
        
        double[][] bigNps = new double[targetSize][targetSize];
        for (int y = 0; y < targetSize; y++) {
            for (int x = 0; x < targetSize; x++) {
                bigNps[y][x] = resized.getf(x, y);
            }
        }
        return bigNps;
    }

    private static void exibirStackNoImageJ(double pixelSize, int matrizSize, double[][] nps2D, double[] ttfFreq, double[] ttfData, double contrast, double radius) {
        
        FloatProcessor ipTemplate = new FloatProcessor(matrizSize, matrizSize);
        double rPixels = radius / pixelSize;
        int center = matrizSize / 2;
        int subSamples = 4; 
        
        for (int y = 0; y < matrizSize; y++) {
            for (int x = 0; x < matrizSize; x++) {
                int insideCount = 0;
                
                for (int sy = 0; sy < subSamples; sy++) {
                    for (int sx = 0; sx < subSamples; sx++) {
                        double subX = (x - center) + (sx + 0.5) / subSamples - 0.5;
                        double subY = (y - center) + (sy + 0.5) / subSamples - 0.5;
                        
                        if (Math.sqrt(subX * subX + subY * subY) <= rPixels) {
                            insideCount++;
                        }
                    }
                }
                
                double coverage = (double) insideCount / (subSamples * subSamples);
                ipTemplate.putPixelValue(x, y, contrast * coverage);
            }
        }

        double[][] mtf2D = new double[matrizSize][matrizSize];
        double df2D = 1.0 / (matrizSize * pixelSize);
        
        for (int y = 0; y < matrizSize; y++) {
            for (int x = 0; x < matrizSize; x++) {
                double fx = (x - center) * df2D;
                double fy = (y - center) * df2D;
                double f = Math.sqrt(fx * fx + fy * fy);
                mtf2D[y][x] = interpolate1D(ttfFreq, ttfData, f);
            }
        }

        ImageProcessor ipMtf = MTFModel.applyMTF(ipTemplate, mtf2D);
        NPSModel npsModel = new NPSModel();
        
        double[][] expandedNps2D = expandNpsToSize(nps2D, matrizSize);
        ImageProcessor ipRuido = npsModel.texturedNoiseIP(expandedNps2D, pixelSize);
        
        FloatProcessor ipFinal = (FloatProcessor) ipMtf.duplicate();
        for (int i = 0; i < ipFinal.getPixelCount(); i++) {
            ipFinal.setf(i, ipFinal.getf(i) + ipRuido.getf(i));
        }

        ImageStack stack = new ImageStack(matrizSize, matrizSize);
        stack.addSlice("1 - Task", ipTemplate);
        stack.addSlice("2 - Noise", ipRuido);
        stack.addSlice("3 - Task*TTF + NPS", ipFinal);

        ImagePlus imp = new ImagePlus("Detectability Task", stack);
        
        double w = Math.max(contrast * 4, 30);
        double l = contrast / 2.0;
        imp.setDisplayRange(l - w/2.0, l + w/2.0);
        
        imp.show();
    }

    private static void gerarPhantomVirtual(double[] ttfFreq, double[] ttfData, double[][] nps2DMedido) {

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Selecione a pasta do BrainWeb Fuzzy");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        String folderPath = chooser.getSelectedFile().getAbsolutePath();

        int bwWidth = 362;
        int bwHeight = 434;
        int slices = 362;
        boolean is16Bit = true;
        double bwPixelSize = 0.5;

        Calibration cal = TtfPanel.getLastCal();
        double realPixelSize = (cal != null && cal.pixelWidth > 0) ? cal.pixelWidth : bwPixelSize;

        double scale = bwPixelSize / realPixelSize;
        int newWidth = (int) Math.round(bwWidth * scale);
        int newHeight = (int) Math.round(bwHeight * scale);

        ImagePlus huImage = BrainWebImporter.createFuzzyPhantom(folderPath, bwWidth, bwHeight, slices, is16Bit);

        double targetSliceThickness = 2.5;
        double origSliceThickness = 0.5;

        ImagePlus thickSlicePhantom = BrainWebImporter.resampleSliceThickness(huImage, targetSliceThickness, origSliceThickness);
        if (thickSlicePhantom == null) {
            return;
        }

        // MTF 2D no grid FINAL (newWidth x newHeight, pixel = realPixelSize)
        double[][] mtf2D = new double[newHeight][newWidth];
        double dfX = 1.0 / (newWidth * realPixelSize);
        double dfY = 1.0 / (newHeight * realPixelSize);
        int centerX = newWidth / 2;
        int centerY = newHeight / 2;

        for (int y = 0; y < newHeight; y++) {
            for (int x = 0; x < newWidth; x++) {
                double fx = (x - centerX) * dfX;
                double fy = (y - centerY) * dfY;
                double f = Math.sqrt(fx * fx + fy * fy);
                mtf2D[y][x] = interpolate1D(ttfFreq, ttfData, f);
            }
        }

        // ---------------------------------------------------------------
        // 1) Reamostra a ANATOMIA LIMPA (HU) para o grid FINAL, montando o
        //    stack no MESMO pixel spacing (realPixelSize) e MESMAS dimensoes
        //    do ruido. O mapa de variancia sera estimado a partir DESTE stack.
        // ---------------------------------------------------------------
        ImageStack originalStack = thickSlicePhantom.getStack();
        int nSlices = originalStack.getSize();

        ImageStack resampledHuStack = new ImageStack(newWidth, newHeight);
        for (int z = 1; z <= nSlices; z++) {
            ImageProcessor ipOriginal = originalStack.getProcessor(z);
            ipOriginal.setInterpolationMethod(ImageProcessor.BICUBIC);
            ImageProcessor ipResized = ipOriginal.resize(newWidth, newHeight);
            resampledHuStack.addSlice("hu_" + z, ipResized);
        }
        ImagePlus resampledHuImage = new ImagePlus("resampled_HU", resampledHuStack);

        // ---------------------------------------------------------------
        // 2) Estima o MAPA DE VARIANCIA (amplitude local) sobre a anatomia
        //    limpa JA reamostrada. pixelSpacing = realPixelSize (coerente).
        //    CALIBRE C via VarianceMapCalibrator ANTES de rodar em anatomia real.
        // ---------------------------------------------------------------
        CTVarianceMapEstimator varEstimator = new CTVarianceMapEstimator(
                595.0,                 // D (mm) - conferir com seu scanner
                1085.6,                // SDD (mm)
                984,                   // numViews
                888,                   // numDetectors
                Math.toRadians(0.07),  // espacamento de canal (rad)
                realPixelSize,         // pixel spacing = grid FINAL (coerente com o stack acima)
                0.25);                 // quarter-detector offset (GE); use 0.0 se nao aplicar
        varEstimator.setMonoenergetic(70.0, 0.19);      // CALIBRAR
        varEstimator.setIncidentPhotonsPerRay(2.5e5);   // CALIBRAR (N0)
        varEstimator.setNormalizationConstant(2.5);     // CALIBRAR (C) - ver VarianceMapCalibrator

        ImagePlus varMapStack = varEstimator.computeVarianceMap(resampledHuImage);
        ImageStack varStack = varMapStack.getStack(); // newWidth x newHeight, nSlices fatias

        // Visualizacao 8-bit para inspecao
        exibirMapaVariancia8bit(varMapStack, "Mapa de Variancia (8-bit)");

        // ---------------------------------------------------------------
        // 3) Loop de sintese: borra (MTF) + injeta ruido texturizado (NPS)
        //    escalado pelo mapa de variancia local.
        // ---------------------------------------------------------------
        ImageStack finalStack = new ImageStack(newWidth, newHeight);
        NPSModel npsModel = new NPSModel();

        int maxSize = Math.max(newWidth, newHeight);
        int targetNoiseSize = 256;
        while (targetNoiseSize < maxSize) {
            targetNoiseSize *= 2;
        }

        double[][] resizedNps2D = expandNpsToSize(nps2DMedido, targetNoiseSize);

        for (int z = 1; z <= nSlices; z++) {
            // Anatomia limpa reamostrada desta fatia (reaproveita o stack ja criado)
            ImageProcessor ipResized = resampledHuStack.getProcessor(z);

            // Borramento pela MTF/TTF
            ImageProcessor ipBorrado = MTFModel.applyMTF(ipResized, mtf2D);

            // 3a. Textura do ruido (da NPS medida) - correlacao/frequencia corretas
            ImageProcessor ipRuidoFull = npsModel.texturedNoiseIP(resizedNps2D, realPixelSize);
            ipRuidoFull.setRoi(0, 0, newWidth, newHeight);
            ImageProcessor ipRuido = ipRuidoFull.crop();

            // 3b. Normaliza para variancia unitaria (remove a amplitude embutida na NPS)
            FloatProcessor ruidoUnit = normalizeToUnitVariance((FloatProcessor) ipRuido.convertToFloat());

            // 3c. Escala pela raiz do mapa de variancia local (mesmas dimensoes agora)
            FloatProcessor varSlice = (FloatProcessor) varStack.getProcessor(z).convertToFloat();
            FloatProcessor ruidoLocal = scaleByStdMap(ruidoUnit, varSlice);

            // 3d. Soma na imagem borrada
            FloatProcessor sliceFim = adicionarRuido(ipBorrado, ruidoLocal);

            finalStack.addSlice("Slice " + z, converterParaShortDICOM(sliceFim));

            IJ.showProgress(z, nSlices);
        }

        ImagePlus impFinal = new ImagePlus("Phantom_Simulado" + DF.format(realPixelSize), finalStack);
        impFinal.setCalibration(cal);
        impFinal.show();

        String savePath = folderPath + File.separator + "Phantom_Simulado_Pixel.tif";
        IJ.saveAs(impFinal, "TIFF", savePath);
        IJ.log("Simulação concluída e salva em: " + savePath);
    }


    private static double interpolate1D(double[] freq, double[] data, double targetF) {
        if (freq == null || freq.length == 0) return 0.0;
        if (targetF >= freq[freq.length - 1]) return 0.0;
        if (targetF <= freq[0]) return data[0];
        
        for (int j = 0; j < freq.length - 1; j++) {
            if (targetF >= freq[j] && targetF <= freq[j + 1]) {
                double f0 = freq[j];
                double f1 = freq[j + 1];
                double m0 = data[j];
                double m1 = data[j + 1];
                return m0 + (targetF - f0) * (m1 - m0) / (f1 - f0);
            }
        }
        return 0.0;
    }

    private static FloatProcessor adicionarRuido(ImageProcessor original, ImageProcessor ruido) {
        FloatProcessor fp = (FloatProcessor) original.convertToFloat();
        FloatProcessor rfp = (FloatProcessor) ruido.convertToFloat();
        for (int i = 0; i < fp.getPixelCount(); i++) {
            fp.setf(i, fp.getf(i) + rfp.getf(i));
        }
        return fp;
    }

    private static ShortProcessor converterParaShortDICOM(FloatProcessor fp) {
        short[] pixels = new short[fp.getPixelCount()];
        for (int i = 0; i < pixels.length; i++) {
            double val = fp.getf(i) + 1024;
            if (val < 0) val = 0;
            if (val > 65535) val = 65535;
            pixels[i] = (short) val;
        }
        return new ShortProcessor(fp.getWidth(), fp.getHeight(), pixels, null);
    }
    /**
     * Normaliza um campo de ruido para variancia empirica ~ 1, preservando a TEXTURA
     * (formato/correlacao) mas removendo a amplitude embutida na NPS medida.
     * Assim a amplitude local passa a ser controlada exclusivamente pelo mapa de variancia.
     */
    private static FloatProcessor normalizeToUnitVariance(FloatProcessor noise) {
        int n = noise.getPixelCount();
        double sum = 0.0, sumSq = 0.0;
        for (int i = 0; i < n; i++) {
            double v = noise.getf(i);
            sum += v;
            sumSq += v * v;
        }
        double mean = sum / n;
        double var = sumSq / n - mean * mean;      // variancia populacional
        double std = (var > 0) ? Math.sqrt(var) : 1.0;

        FloatProcessor out = new FloatProcessor(noise.getWidth(), noise.getHeight());
        for (int i = 0; i < n; i++) {
            out.setf(i, (float) ((noise.getf(i) - mean) / std)); // media 0, variancia 1
        }
        return out;
    }

    /**
     * Escala pixel a pixel um campo de ruido de variancia unitaria pela raiz do mapa
     * de variancia local, produzindo ruido com sigma(x,y) = sqrt(varianceMap(x,y)).
     * varianceMap deve ter as mesmas dimensoes de noiseUnit.
     */
    private static FloatProcessor scaleByStdMap(FloatProcessor noiseUnit, FloatProcessor varianceMap) {
        int w = noiseUnit.getWidth();
        int h = noiseUnit.getHeight();
        if (varianceMap.getWidth() != w || varianceMap.getHeight() != h) {
            throw new IllegalArgumentException("varianceMap e noiseUnit precisam ter as mesmas dimensoes.");
        }
        FloatProcessor out = new FloatProcessor(w, h);
        int n = noiseUnit.getPixelCount();
        for (int i = 0; i < n; i++) {
            double var = varianceMap.getf(i);
            double std = (var > 0) ? Math.sqrt(var) : 0.0;
            out.setf(i, (float) (noiseUnit.getf(i) * std));
        }
        return out;
    }

    /**
     * Rotina Batch para processar TODA a árvore de diretórios carregada no DicomProcessor.
     */
    private static void runBatchAcrRoutine() {
        if (DicomProcessor.patientData == null || DicomProcessor.patientData.isEmpty()) {
            IJ.error("A árvore de diretórios está vazia. Abra uma pasta raiz no File Browser primeiro.");
            return;
        }

        // 1. Coleta os parâmetros que serão aplicados a TODAS as séries
        GenericDialog gd = new GenericDialog("Batch ACR Optimization Parameters");
        gd.addMessage("Parâmetros globais para o Phantom ACR:");
        
        String[] materials = {"Bone", "Air", "Acrylic", "Polyethylene"};
        gd.addChoice("Target Material (TTF):", materials, "Bone");
        
        gd.addNumericField("Number of ROIs (NPS):", 12, 0);
        gd.addNumericField("ROI Size (px):", 64, 0);
        gd.addNumericField("NPS Distribution Radius (mm):", 40.0, 1);
        
        gd.addMessage("Parâmetros da Tarefa Diagnóstica:");
        gd.addNumericField("Task Radius (mm):", Double.parseDouble(taskRadiusField.getText().trim()), 1);
        gd.addNumericField("Task Contrast (HU):", Double.parseDouble(taskContrastField.getText().trim()), 1);
        gd.addNumericField("Viewing Distance (mm):", Double.parseDouble(viewDistanceField.getText().trim()), 1);
        gd.addNumericField("Display Size (mm):", Double.parseDouble(displaySizeField.getText().trim()), 1);
        gd.addNumericField("Display FOV (mm):", Double.parseDouble(fovField.getText().trim()), 1);
        
        gd.showDialog();
        if (gd.wasCanceled()) return;

        String targetMaterial = gd.getNextChoice();
        int numRois     = (int) gd.getNextNumber();
        int roiSizePx   = (int) gd.getNextNumber();
        double distRad  = gd.getNextNumber();
        double tRad     = gd.getNextNumber();
        double tCont    = gd.getNextNumber();
        double vDist    = gd.getNextNumber();
        double dSize    = gd.getNextNumber();
        double fov      = gd.getNextNumber();

        // 2. Conta quantas tarefas temos no total para a barra de progresso
        int tempTotalTasks = 0;
        for (java.util.Map.Entry<String, java.util.Map<String, java.util.Map<String, java.util.Map<String, SeriesData>>>> patientEntry : DicomProcessor.patientData.entrySet()) {
            for (java.util.Map.Entry<String, java.util.Map<String, java.util.Map<String, SeriesData>>> studyEntry : patientEntry.getValue().entrySet()) {
                for (java.util.Map.Entry<String, java.util.Map<String, SeriesData>> seriesGroupEntry : studyEntry.getValue().entrySet()) {
                    if (studyEntry.getKey().equals("Patient Protocol")) continue;
                    tempTotalTasks += seriesGroupEntry.getValue().size();
                }
            }
        }
        final int totalTasks = tempTotalTasks;
        
        if (totalTasks == 0) {
            IJ.error("Nenhuma série de imagens encontrada para análise.");
            return;
        }

        // Usando a sua classe de loading dialog (se disponível)
        javax.swing.JDialog dialog = createLoadingDialog("Processing " + totalTasks + " ACR series...");
        javax.swing.JProgressBar progressBar = (javax.swing.JProgressBar) dialog.getContentPane().getComponent(1);

        List<BatchResult> batchResults = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.concurrent.atomic.AtomicInteger processed = new java.util.concurrent.atomic.AtomicInteger(0);

        // 3. Roda em background para não congelar a interface
        new Thread(() -> {
            try {
                for (java.util.Map.Entry<String, java.util.Map<String, java.util.Map<String, java.util.Map<String, SeriesData>>>> patientEntry : DicomProcessor.patientData.entrySet()) {
                    String patientName = patientEntry.getKey();
                    
                    for (java.util.Map.Entry<String, java.util.Map<String, java.util.Map<String, SeriesData>>> studyEntry : patientEntry.getValue().entrySet()) {
                        String studyDescription = studyEntry.getKey();
                        if (studyDescription.equals("Patient Protocol")) continue;

                        for (java.util.Map.Entry<String, java.util.Map<String, SeriesData>> protocolEntry : studyEntry.getValue().entrySet()) {
                            String protocol = protocolEntry.getKey();
                            
                            for (java.util.Map.Entry<String, SeriesData> seriesEntry : protocolEntry.getValue().entrySet()) {
                                SeriesData seriesData = seriesEntry.getValue();
                                String seriesDescription = seriesData.toString();
                                
                                if (!seriesDescription.equals("Patient Protocol")) {
                                    
                                    // Torna a série ativa provisoriamente para o ImageOpener (necessário pro AcrAutoDetectability)
                                    ImageOpener.activeSeriesData = seriesData;
                                    
                                    IJ.log("\nProcessando Lote: " + seriesDescription);
                                    
                                    // Tenta rodar a detecção automática
                                    AcrAutoDetectability.AutoDetectResult res = AcrAutoDetectability.runAutoACR(
                                            targetMaterial, numRois, roiSizePx, distRad, tRad, tCont, vDist, dSize, fov);
                                    
                                    if (res != null) {
                                        // Extração dos Metadados em tempo real (Headless)
                                        List<File> files = seriesData.getFiles();
                                        if (files != null && !files.isEmpty()) {
                                            String metadata = seriesData.getMetadata(files.get(0));
                                            
                                            String studyDate = DicomProcessor.getTag(metadata, "0008,0020");
                                            String kernel = DicomProcessor.getTag(metadata, "0018,1210");
                                            String sliceThick = DicomProcessor.getTag(metadata, "0018,0050");
                                            String kvp = DicomProcessor.getTag(metadata, "0018,0060");
                                            // mA varia entre fabricantes (GE usa Tube Current 1151, ou Exposure 1152)
                                            String ma = DicomProcessor.getTag(metadata, "0018,1151");
                                            if (ma == null) ma = DicomProcessor.getTag(metadata, "0018,1152");

                                            synchronized (batchResults) {
                                                batchResults.add(new BatchResult(
                                                    patientName, studyDate, studyDescription, protocol, seriesDescription,
                                                    kernel, sliceThick, kvp, ma, targetMaterial, 
                                                    res.dPrime, res.auc, res.globalNoise, res.f50, res.f10
                                                ));
                                            }
                                        }
                                    } else {
                                        IJ.log("  >> AVISO: Falha ao processar a série " + seriesDescription + ". Ignorada no Excel.");
                                    }
                                }
                                
                                int progress = (int) ((double) processed.incrementAndGet() * 100 / totalTasks);
                                javax.swing.SwingUtilities.invokeLater(() -> progressBar.setValue(progress));
                            }
                        }
                    }
                }
                
                javax.swing.SwingUtilities.invokeLater(() -> {
                    dialog.dispose();
                    // Invoca a classe de Excel!
                    DetectabilityExcelExporter.exportBatchResults(batchResults);
                });
                
            } catch (Exception e) {
                e.printStackTrace();
                IJ.handleException(e);
                javax.swing.SwingUtilities.invokeLater(() -> dialog.dispose());
            }
        }).start();
    }
    public static JDialog createLoadingDialog(String message) {
        JDialog dialog = new JDialog(DetectabilidadePlugin.frame, "", true);
        dialog.setSize(300, 100);
        dialog.setLocationRelativeTo(DetectabilidadePlugin.frame);
        dialog.setLayout(new BorderLayout());

        JLabel label = new JLabel(message, JLabel.CENTER);
        JProgressBar progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        progressBar.setValue(0);

        dialog.add(label, BorderLayout.NORTH);
        dialog.add(progressBar, BorderLayout.CENTER);

        SwingUtilities.invokeLater(() -> dialog.setVisible(true));
        return dialog;
    }
    /**
     * Exibe um mapa de variancia (stack de FloatProcessor, valores em HU^2) como uma
     * ImagePlus em tons de cinza 8-bit. Cada valor de variancia e mapeado linearmente
     * para o intervalo [0, 255] usando o minimo e o maximo GLOBAIS do stack inteiro,
     * de modo que o contraste seja consistente entre as fatias.
     *
     * Regioes de maior variancia (ruido mais forte) aparecem mais claras;
     * regioes de menor variancia, mais escuras.
     *
     * @param varianceMap stack do mapa de variancia (ex.: saida de computeVarianceMap).
     * @param title       titulo da janela ImagePlus resultante.
     * @return ImagePlus 8-bit em tons de cinza (mesmas dimensoes e numero de fatias).
     */
    private static ImagePlus exibirMapaVariancia8bit(ImagePlus varianceMap, String title) {
        ImageStack src = varianceMap.getStack();
        int w = src.getWidth();
        int h = src.getHeight();
        int nSlices = src.getSize();

        // 1. Encontra minimo e maximo globais sobre todas as fatias (ignora NaN/Inf).
        double globalMin = Double.POSITIVE_INFINITY;
        double globalMax = Double.NEGATIVE_INFINITY;
        for (int s = 1; s <= nSlices; s++) {
            FloatProcessor fp = src.getProcessor(s).convertToFloatProcessor();
            int n = fp.getPixelCount();
            for (int i = 0; i < n; i++) {
                double v = fp.getf(i);
                if (Double.isNaN(v) || Double.isInfinite(v)) continue;
                if (v < globalMin) globalMin = v;
                if (v > globalMax) globalMax = v;
            }
        }

        // Protecao contra stack constante/vazio.
        if (!Double.isFinite(globalMin) || !Double.isFinite(globalMax)) {
            globalMin = 0.0;
            globalMax = 0.0;
        }
        double range = globalMax - globalMin;
        double scale = (range > 0) ? 255.0 / range : 0.0;

        // 2. Converte cada fatia para 8-bit aplicando o janelamento linear global.
        ImageStack out = new ImageStack(w, h);
        for (int s = 1; s <= nSlices; s++) {
            FloatProcessor fp = src.getProcessor(s).convertToFloatProcessor();
            int n = fp.getPixelCount();
            byte[] pixels = new byte[n];
            for (int i = 0; i < n; i++) {
                double v = fp.getf(i);
                if (Double.isNaN(v) || Double.isInfinite(v)) v = globalMin;
                int g = (int) Math.round((v - globalMin) * scale);
                if (g < 0) g = 0;
                if (g > 255) g = 255;
                pixels[i] = (byte) g;
            }
            out.addSlice(src.getSliceLabel(s), new ByteProcessor(w, h, pixels, null));
        }

        ImagePlus imp = new ImagePlus(title, out);
        imp.show();
        return imp;
    }

    // ------------------------------------------------------------------
    // API de integracao com a aba "CQ ACR"
    // ------------------------------------------------------------------

    /** Parametros de visualizacao pre-configurados no eye filter (viewDist, displaySize, fov). */
    public static double[] getEyeFilterParams() {
        double vDist = parseOr(viewDistanceField, 500.0);
        double dSize = parseOr(displaySizeField, 305.0);
        double fov   = parseOr(fovField, 250.0);
        return new double[]{vDist, dSize, fov};
    }

    private static double parseOr(JTextField f, double def) {
        try { return Double.parseDouble(f.getText().trim()); } catch (Exception e) { return def; }
    }

    /**
     * Publica no painel NPWE um resultado de d'/AUC calculado externamente
     * (ex.: pelo fluxo automatico da aba "CQ ACR"), atualizando tambem os campos
     * de parametros da tarefa usados no calculo.
     */
    public static void publishExternalResult(double dPrime, double auc,
                                             double taskRadius, double taskContrast,
                                             double[] ttfFreq, double[] ttfData,
                                             double[] npsFreq, double[] npsData, double[][] nps2D) {
        if (dPrimeResultField != null) dPrimeResultField.setText(DF.format(dPrime));
        if (AucResultField != null)    AucResultField.setText(DF.format(auc));
        if (taskRadiusField != null)   taskRadiusField.setText(String.valueOf(taskRadius));
        if (taskContrastField != null) taskContrastField.setText(String.valueOf(taskContrast));

        lastTtfFreq = ttfFreq;
        lastTtfData = ttfData;
        lastNpsFreq = npsFreq;
        lastNpsData = npsData;
        lastNps2D = nps2D;
        lastTaskContrast = taskContrast;
        lastTaskRadius = taskRadius;

        if (btnShowImages != null) btnShowImages.setEnabled(ttfData != null && nps2D != null);
        if (btnGeneratePhantom != null) btnGeneratePhantom.setEnabled(ttfData != null && nps2D != null);
    }
}