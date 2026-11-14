# Projeto: Plugin ACR CT QC (Plugin-Novo / Rede D'Or)

## Visão geral
Plugin ImageJ2/SciJava para análise de qualidade de imagem de TC e controle de
qualidade (CQ) com o phantom ACR. Evoluindo de uma ferramenta de detectabilidade
(TTF/NPS/NPWE) para uma suíte completa de testes da ANVISA:
exatidão do número de CT, uniformidade, espessura de corte, baixo contraste/CNR,
exatidão de distância, e modulação de corrente, além do índice de detectabilidade.

## Convenções de código
- Pacote Java: `br.rededor.fisicamedica` (NÃO usar `br.unifesp.fisicamedica`,
  que é o pacote do repositório anterior `Detectabilidade`).
- Java target: `release 8` (configurado no `pom.xml`).
- Diretório do módulo Maven: `Plugin-Novo/Plugin_RedeD'or/` (atenção ao apóstrofo
  no nome — sempre citar o caminho entre aspas em comandos de shell).
- Artefato Maven atual: `br.unifesp:SSDE:0.1.0` (metadados legados; manter até
  rebrand explícito).
- Entry point SciJava: `@Plugin(type = Command.class, menuPath = "Plugins>Detectabilidade")`
  em `DetectabilidadePlugin.java`.

## Arquitetura da UI
- `DetectabilidadePlugin`: `JFrame` com `JSplitPane`. Esquerda = árvore DICOM
  (CardLayout BUTTON/TREE). Direita = `TopPanel`.
- `TopPanel.create()`: `JTabbedPane` com as abas de análise. Novos painéis entram
  como abas adicionais aqui.
- Estado entre painéis é compartilhado via métodos estáticos (ex.:
  `TtfPanel.getLastTtfResult()`, `NPSpanel.getLastRadialNPS()`), não por injeção.
- A "série ativa" é lida de `ImageOpener.getActiveSeriesData()` (setada ao dar
  duplo-clique numa série na árvore).

## Carregamento DICOM
- `DicomProcessor.analyzeDirectory` indexa Paciente→Estudo→Protocolo→SérieUID.
- `DicomProcessor.getTag(metadata, "GGGG,EEEE")` extrai tags do dump textual do
  ImageJ (`new DICOM().getInfo(...)`). É frágil (depende do formato textual) —
  reutilizar, mas tratar null.
- Tags úteis: espessura de corte `0018,0050`, spacing `0018,0088`,
  posição `0020,1041`, corrente mA `0018,1151`, kVp `0018,0060`, kernel `0018,1210`.

## Build (neste sandbox)
- `$HOME` é read-only; usar repositório local Maven gravável:
  `-Dmaven.repo.local=/projects/sandbox/.m2repo`
- Java 25 é o default, mas o projeto compila com `release 8`; usar JDK 17 para o
  build: `JAVA_HOME=/root/.local/share/mise/installs/java/17.0.2`.
- Compilar: `mvn -o -Dmaven.repo.local=/projects/sandbox/.m2repo compile`
  (usar online na primeira vez / para `clean`).

## Migração do repositório `Detectabilidade`
Ao portar código de `br.unifesp.fisicamedica` (repo Detectabilidade) para este
projeto, reconciliar divergências de nomes de método que existem entre os repos:
- `TtfCalc.analyzeInsertStack` (Detectabilidade) == `analisarStackComInserts`
  (Plugin-Novo). `AcrAutoDetectability` chama a versão em português — manter
  compatibilidade ou atualizar as chamadas.
- `NPSpanel.findPeakFrequency` (Detectabilidade) == `encontrarFrequenciaDePico`
  (Plugin-Novo).
- Sempre trocar o `package` para `br.rededor.fisicamedica` nos arquivos portados.

## Phantom ACR — geometria de referência (da macro de validação)
- Área do phantom ≈ 31.400 mm² (corpo com HU > -500).
- Módulo 1 (insertos): assinatura = presença simultânea de Ar (< -700 HU) e
  Osso (> 700 HU). Materiais: Água, Osso, Ar, Polietileno, Acrílico.
- Módulos espaçados ~40 mm entre si (M1↔M2 ±40 mm, M1↔M3 ±80 mm).
- Teste de espessura de corte: rampas; contagem de picos acima de meia-altura
  (threshold = max/2), distância mínima entre picos 3 mm.
- Uniformidade (Módulo 3): ROI central + 4 periféricos (12h/3h/6h/9h).
- A saída de relatório preferida é uma linha única com valores separados por
  TAB (decimal com vírgula) para colar no Excel, replicando a macro existente.

## Imagens de validação
- Usar a série **CRANIO S_C** do phantom ACR para validação.
- NÃO usar `Detectabilidade/PORTAL 0.625mm-1.tif`.

## Abertura automatica na inicializacao do ImageJ
- `AutoStartService` (um `@Plugin(type = Service.class)`) escuta o
  `UIShownEvent` do SciJava e executa `DetectabilidadePlugin` assim que a
  interface do ImageJ aparece. Abre apenas uma vez e so quando ha GUI (em
  headless o evento nao e disparado).
- Para isso funcionar, o indice de anotacoes
  `META-INF/json/org.scijava.plugin.Plugin` precisa ser gerado no build. O
  `maven-compiler-plugin` foi configurado com `<annotationProcessors>` incluindo
  `org.scijava.annotations.AnnotationProcessor` — sem isso, com `release 8` +
  JDK 17, o Maven nao roda o processor e o indice (e o autostart) nao e gerado.
- Para desabilitar a abertura automatica, remover `AutoStartService.java`.

## Auditoria visual das análises ACR
- Toda ROI efetivamente usada por um teste ACR deve ser preservada como
  `AcrQcAnalyser.RoiRecord`, com nome descritivo e posição global da fatia.
- Isso inclui ROIs de número de CT, ar externo, uniformidade, CNR, rampas de
  espessura, janelas/pontos/linha da distância, contornos TTF e ROIs NPS.
- O analisador não deve manipular o `RoiManager`: a publicação ocorre em lote na
  EDT pelo `AcrQcPanel`, após confirmar que a série e a imagem ativas não mudaram.
- Não usar `RoiManager.reset()` no fluxo ACR. Preservar ROIs do usuário e nomear
  cada execução com um identificador próprio.
- A aba CQ ACR deve apresentar resultados em componentes Swing estruturados
  (cards, tabelas e gráficos úteis), não em uma `JTextArea` monolítica. Os
  gráficos completos de TTF e NPS permanecem apenas nas respectivas abas.
