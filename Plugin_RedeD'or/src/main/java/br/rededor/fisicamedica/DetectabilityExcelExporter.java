package br.rededor.fisicamedica;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import ij.IJ;

public class DetectabilityExcelExporter {

    public static void exportBatchResults(List<BatchResult> results) {
        if (results == null || results.isEmpty()) {
            IJ.error("Nenhum resultado para exportar.");
            return;
        }

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Save Batch Results");
        fileChooser.setFileFilter(new FileNameExtensionFilter("Excel Workbook (*.xlsx)", "xlsx"));
        fileChooser.setSelectedFile(new File("ACR_Detectability_Optimization.xlsx"));

        int userSelection = fileChooser.showSaveDialog(null);
        if (userSelection != JFileChooser.APPROVE_OPTION) {
            return;
        }

        File fileToSave = fileChooser.getSelectedFile();
        if (!fileToSave.getName().toLowerCase().endsWith(".xlsx")) {
            fileToSave = new File(fileToSave.getParentFile(), fileToSave.getName() + ".xlsx");
        }

        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Optimization Results");

            // Estilo do Cabeçalho (Negrito)
            CellStyle headerStyle = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            headerStyle.setFont(font);

            // Criar a linha de cabeçalho
            String[] columns = {
                "Patient Name", "Study Date", "Study Description", "Protocol", 
                "Series Description", "Kernel", "Slice Thick. (mm)", "kVp", "mA / Exposure",
                "Target Material", "d' (Detectability)", "AUC", "Global Noise (HU)", 
                "f50 (lp/mm)", "f10 (lp/mm)"
            };

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < columns.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(columns[i]);
                cell.setCellStyle(headerStyle);
            }

            // Preencher os dados
            int rowNum = 1;
            for (BatchResult res : results) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(res.patientName != null ? res.patientName : "");
                row.createCell(1).setCellValue(res.studyDate != null ? res.studyDate : "");
                row.createCell(2).setCellValue(res.studyDescription != null ? res.studyDescription : "");
                row.createCell(3).setCellValue(res.protocol != null ? res.protocol : "");
                row.createCell(4).setCellValue(res.seriesDescription != null ? res.seriesDescription : "");
                row.createCell(5).setCellValue(res.kernel != null ? res.kernel : "");
                
                // Tratar números armazenados como String no DICOM
                safeSetNumericCell(row.createCell(6), res.sliceThickness);
                safeSetNumericCell(row.createCell(7), res.kvp);
                safeSetNumericCell(row.createCell(8), res.ma);
                
                row.createCell(9).setCellValue(res.targetMaterial != null ? res.targetMaterial : "");
                
                row.createCell(10).setCellValue(res.dPrime);
                row.createCell(11).setCellValue(res.auc);
                row.createCell(12).setCellValue(res.globalNoise);
                row.createCell(13).setCellValue(res.f50);
                row.createCell(14).setCellValue(res.f10);
            }

            // Auto-ajustar largura das colunas
            for (int i = 0; i < columns.length; i++) {
                sheet.autoSizeColumn(i);
            }

            // Escrever no disco
            try (FileOutputStream out = new FileOutputStream(fileToSave)) {
                workbook.write(out);
            }
            
            IJ.log("Resultados em lote exportados com sucesso para:\n" + fileToSave.getAbsolutePath());
            JOptionPane.showMessageDialog(null, "Exportação concluída com sucesso!", "Excel Export", JOptionPane.INFORMATION_MESSAGE);

        } catch (Exception e) {
            IJ.handleException(e);
            JOptionPane.showMessageDialog(null, "Erro ao salvar arquivo Excel:\n" + e.getMessage(), "Erro", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void safeSetNumericCell(Cell cell, String value) {
        if (value == null || value.trim().isEmpty()) {
            cell.setCellValue("");
            return;
        }
        try {
            cell.setCellValue(Double.parseDouble(value));
        } catch (NumberFormatException e) {
            cell.setCellValue(value); // Se falhar, salva como texto mesmo
        }
    }
}