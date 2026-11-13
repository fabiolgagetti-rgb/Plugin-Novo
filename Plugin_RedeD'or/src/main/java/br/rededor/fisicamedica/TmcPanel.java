package br.rededor.fisicamedica;

import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;

import org.jfree.chart.ChartPanel;

import ij.IJ;

/**
 * Painel da Modulacao de Corrente (TMC) portado do projeto CT_TMC.
 *
 * Calcula, ao longo da serie DICOM ativa, o diametro equivalente de agua (Dw),
 * a corrente (mA) e o ruido em funcao da posicao de mesa, exibindo o grafico
 * (via {@link TmcGraphManager}) e a tabela de dados (via
 * {@link TmcTabelaDadosPanel}).
 *
 * Observacao: o botao "ACR Analysis" do painel original foi removido, pois a
 * analise ACR ja e feita pela aba "CQ ACR" (AcrQcPanel) deste plugin.
 */
public class TmcPanel {

    private static ChartPanel chartPanel;
    static TmcCalc tmcCalc = new TmcCalc();

    public static JPanel create() {
        JPanel rightSidePanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.NORTH;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Modulacao de Corrente"));
        addComponents(panel);
        panel.setMinimumSize(new Dimension(250, 500));

        chartPanel = TmcGraphManager.createChartPanel();

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.fill = GridBagConstraints.BOTH;
        rightSidePanel.add(panel, gbc);

        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.fill = GridBagConstraints.BOTH;
        chartPanel.setMinimumSize(new Dimension(600, 500));
        rightSidePanel.add(chartPanel, gbc);

        return rightSidePanel;
    }

    private static void addComponents(JPanel panel) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JButton calculateButton = new JButton("Calculate");
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.CENTER;
        panel.add(calculateButton, gbc);

        double[] array = {0};
        TmcTabelaDadosPanel tabelaPanel = new TmcTabelaDadosPanel(array, array, array, array);
        gbc.gridx = 0;
        gbc.gridy = 1;
        panel.add(tabelaPanel, gbc);
        panel.revalidate();
        panel.repaint();

        calculateButton.addActionListener(e -> {
            try {
                tmcCalc.run();
                updateResults();
                // Remove a tabela anterior (componente de indice 1) e recria com os dados.
                if (panel.getComponentCount() > 1) {
                    panel.remove(1);
                }

                TmcTabelaDadosPanel tabelaPanel2 = new TmcTabelaDadosPanel(
                        tmcCalc.position, tmcCalc.Dw, tmcCalc.mA, tmcCalc.stdev);
                gbc.gridx = 0;
                gbc.gridy = 1;
                gbc.weightx = 1.0;
                gbc.weighty = 1.0;
                gbc.fill = GridBagConstraints.BOTH;
                gbc.gridwidth = GridBagConstraints.REMAINDER;

                panel.add(tabelaPanel2, gbc);
                panel.revalidate();
                panel.repaint();
            } catch (NumberFormatException ex) {
                IJ.error("Unable to calculate");
            }
        });
    }

    private static void updateResults() {
        TmcGraphManager.updateChart(tmcCalc);
    }
}
