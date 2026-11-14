package br.rededor.fisicamedica;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;

public class TmcTabelaDadosPanel extends JPanel {
    private DefaultTableModel model;
    private JTable table;
    
    public TmcTabelaDadosPanel(double[] slice, double[] Dw, double[] mA, double[] stdev) {
        setLayout(new BorderLayout());
        
        model = new DefaultTableModel() {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        
        model.addColumn("Position");
        model.addColumn("Dw");
        model.addColumn("mA");
        model.addColumn("Stdev");
        
        // Preencher tabela
        fillTable(slice, Dw, mA, stdev);
        
        table = new JTable(model);
        configureTable();
        
        JScrollPane scrollPane = new JScrollPane(table);
        add(scrollPane, BorderLayout.CENTER);
    }
    
    private void fillTable(double[] slice, double[] Dw, double[] mA, double[] stdev) {
        model.setRowCount(0); // Limpa dados existentes
        
        for (int i = 0; i < slice.length; i++) {
            model.addRow(new Object[]{
                slice[i],
                Dw[i],
                mA[i],
                stdev[i]
            });
        }
    }
    
    private void configureTable() {
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setFillsViewportHeight(true);
        
        // Configurar larguras das colunas
        table.getColumnModel().getColumn(0).setPreferredWidth(80);
        table.getColumnModel().getColumn(1).setPreferredWidth(120);
        table.getColumnModel().getColumn(2).setPreferredWidth(120);
        table.getColumnModel().getColumn(3).setPreferredWidth(120);
        
        // Habilitar rolagem horizontal
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    }
    
    public void updateData(double[] slice, double[] Dw, double[] mA, double[] stdev) {
        fillTable(slice, Dw, mA, stdev);
    }
}