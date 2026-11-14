package br.rededor.fisicamedica;

import org.scijava.command.CommandService;
import org.scijava.event.EventHandler;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.service.AbstractService;
import org.scijava.service.Service;
import org.scijava.ui.event.UIShownEvent;

/**
 * Service do SciJava que abre automaticamente o plugin (DetectabilidadePlugin)
 * assim que a interface grafica do ImageJ e exibida na inicializacao.
 *
 * Como funciona:
 *  - Services do SciJava sao instanciados automaticamente quando o Context e
 *    criado (no boot do ImageJ), portanto este handler ja esta registrado a
 *    tempo de capturar o evento de UI pronta.
 *  - {@link UIShownEvent} e disparado quando a janela principal do ImageJ
 *    aparece. Ao receber esse evento (apenas uma vez), executamos o comando
 *    {@link DetectabilidadePlugin}.
 *
 * Observacao: se por algum motivo nao for desejado abrir na inicializacao,
 * basta remover esta classe (ou o arquivo de servico gerado); o plugin
 * continua acessivel normalmente pelo menu Plugins &gt; Detectabilidade.
 */
@Plugin(type = Service.class)
public class AutoStartService extends AbstractService {

    @Parameter
    private CommandService commandService;

    /** Garante que o comando seja aberto apenas uma vez. */
    private volatile boolean launched = false;

    @EventHandler
    public void onUIShown(final UIShownEvent evt) {
        if (launched) return;
        launched = true;
        // Dispara o comando na EDT do SciJava; headless=false garante a GUI.
        commandService.run(DetectabilidadePlugin.class, true);
    }
}
