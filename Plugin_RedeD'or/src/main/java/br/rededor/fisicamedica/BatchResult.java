package br.rededor.fisicamedica;

public class BatchResult {
    // 1. Identificação do Exame
    public String patientName;
    public String studyDate;
    public String studyDescription;
    public String protocol;
    public String seriesDescription;
    
    // 2. Parâmetros Técnicos (Cabeçalho DICOM)
    public String kernel;
    public String sliceThickness;
    public String kvp;
    public String ma;
    
    // 3. Parâmetros da Tarefa
    public String targetMaterial;
    
    // 4. Métricas de Qualidade Calculadas
    public double dPrime;
    public double auc;
    public double globalNoise; // Desvio padrão médio (HU)
    public double f50;         // Frequência a 50% da TTF (lp/mm ou cy/mm)
    public double f10;         // Frequência a 10% da TTF

    // Construtor completo
    public BatchResult(String patientName, String studyDate, String studyDescription, String protocol,
                       String seriesDescription, String kernel, String sliceThickness, String kvp, String ma,
                       String targetMaterial, double dPrime, double auc, double globalNoise, double f50, double f10) {
        this.patientName = patientName;
        this.studyDate = studyDate;
        this.studyDescription = studyDescription;
        this.protocol = protocol;
        this.seriesDescription = seriesDescription;
        this.kernel = kernel;
        this.sliceThickness = sliceThickness;
        this.kvp = kvp;
        this.ma = ma;
        this.targetMaterial = targetMaterial;
        this.dPrime = dPrime;
        this.auc = auc;
        this.globalNoise = globalNoise;
        this.f50 = f50;
        this.f10 = f10;
    }
}