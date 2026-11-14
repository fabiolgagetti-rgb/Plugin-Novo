package br.rededor.fisicamedica;


import java.awt.BorderLayout;
import java.awt.Dimension;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

/**
 * Painel principal que organiza as ferramentas de análise de imagem em abas.
 * Cada aba carrega um painel de funcionalidade específica (TTF, NPS, d').
 */
public class TopPanel {

    /**
     * Cria e retorna o painel com todas as abas de análise.
     * @return um JPanel contendo o JTabbedPane.
     */
    public static JPanel create() {
        JPanel rootPanel = new JPanel(new BorderLayout());
        JTabbedPane tabbedPane = new JTabbedPane();
        JPanel mtfPanel = TtfPanel.create();
        
        JPanel npsPanel = NPSpanel.create();
        JPanel dPrimePanel = DetectabilityIndexPanel.create();
        JPanel acrQcPanel = AcrQcPanel.create();
        JPanel tmcPanel = TmcPanel.create();

        tabbedPane.addTab("CQ ACR", acrQcPanel);
        tabbedPane.addTab("TTF", mtfPanel);
        tabbedPane.addTab("NPS", npsPanel);
        tabbedPane.addTab("NPWE Model Observer", dPrimePanel);
        tabbedPane.addTab("Modulacao de Corrente", tmcPanel);

        rootPanel.add(tabbedPane, BorderLayout.CENTER);
        
        rootPanel.setPreferredSize(new Dimension(850, 600));

        return rootPanel;
    }
}
