package br.rededor.fisicamedica;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Paint;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

public class TmcGraphManager {
    
    private static ChartPanel chartPanel;

    public static ChartPanel createChartPanel() {
        chartPanel = new ChartPanel(null);
        chartPanel.setPreferredSize(new java.awt.Dimension(600, 300));
        JFreeChart emptyChart = createEmptyChart();
        chartPanel = new ChartPanel(emptyChart);
        chartPanel.setPreferredSize(new Dimension(600, 400));
        chartPanel.setMinimumSize(new Dimension(500, 300));
        return chartPanel;
    }
    private static JFreeChart createEmptyChart() {
        DefaultCategoryDataset emptyDataset = new DefaultCategoryDataset();
        return ChartFactory.createLineChart(
                "",
                "Position", "Value",
                emptyDataset,
                PlotOrientation.VERTICAL,
                true, true, false);
    }

    public static void updateChart(TmcCalc TmcCalc) {

       
        XYSeriesCollection  dataset = new XYSeriesCollection ();
        XYSeriesCollection  dataset2 = new XYSeriesCollection ();
        String yAxis = "Corrente (mA)";
       
            addSeriesToDataset(dataset, TmcCalc.position, TmcCalc.mA, "Corrente (mA)");
            addSeriesToDataset(dataset2, TmcCalc.position, TmcCalc.Dw, "Dw (cm)");
            addSeriesToDataset(dataset2, TmcCalc.position, TmcCalc.stdev, "Ruído");
          

        JFreeChart chart = ChartFactory.createXYLineChart(
                "",
                "Posição", yAxis,
                dataset,
                PlotOrientation.VERTICAL,
                true, true, false
        );
        XYPlot plot = chart.getXYPlot();
        NumberAxis secondaryAxis = new NumberAxis("Dw(cm)      Ruído");
        plot.setRangeAxis(1, secondaryAxis);
        plot.setDataset(1, dataset2);
        plot.mapDatasetToRangeAxis(1, 1);
        
     // Obter os renderers para ambos os datasets
        XYLineAndShapeRenderer primaryRenderer = (XYLineAndShapeRenderer) plot.getRendererForDataset(dataset);
        XYLineAndShapeRenderer secondaryRenderer = new XYLineAndShapeRenderer();
        plot.setRenderer(1, secondaryRenderer);

        // Configurar cores e remover shapes para todas as séries do primeiro dataset
        for (int i = 0; i < dataset.getSeriesCount(); i++) {
            primaryRenderer.setSeriesShapesVisible(i, false);  // Remove os marcadores
            primaryRenderer.setSeriesLinesVisible(i, true);   // Mantém as linhas
            
            // Define cores diferentes para cada série (você pode personalizar)
            primaryRenderer.setSeriesPaint(i, getSeriesColor(i));
        }

        // Configurar cores e remover shapes para todas as séries do segundo dataset
        for (int i = 0; i < dataset2.getSeriesCount(); i++) {
            secondaryRenderer.setSeriesShapesVisible(i, false);
            secondaryRenderer.setSeriesLinesVisible(i, true);
            
            // Usa cores diferentes das do primeiro dataset
            secondaryRenderer.setSeriesPaint(i, getSeriesColor(i + dataset.getSeriesCount()));
        }

        

        chartPanel.setChart(chart);
        chartPanel.revalidate();
        chartPanel.repaint();
    }
    public static void addSeriesToDataset(XYSeriesCollection dataset, double[] xValues, double[] yValues, String seriesName) {
        XYSeries series = new XYSeries(seriesName);
        for (int i = 0; i < xValues.length; i++) {
            series.add(xValues[i], yValues[i]);
        }
        dataset.addSeries(series);
    }
 // Método auxiliar para gerar cores diferentes para cada série
    private static Paint getSeriesColor(int seriesIndex) {
        // Exemplo de paleta de cores - personalize conforme necessário
        Color[] colors = new Color[] {
            Color.BLUE, Color.RED, Color.GREEN
        };
        return colors[seriesIndex % colors.length];
    }
}