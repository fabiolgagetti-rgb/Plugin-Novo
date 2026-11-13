package br.rededor.fisicamedica;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import ij.IJ;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class BrainWebImporter {

    /**
     * Lê um arquivo .raw do BrainWeb e converte para um ImageStack de 16-bits do ImageJ.
     * 
     * @param filePath Caminho completo para o arquivo .raw ou .raws
     * @param width    Largura da imagem (X) - Padrão BrainWeb: 181
     * @param height   Altura da imagem (Y) - Padrão BrainWeb: 217
     * @param slices   Número de fatias (Z) - Padrão BrainWeb: 181
     * @return Um ImagePlus contendo o stack volumétrico, ou null em caso de erro.
     */
    public static ImagePlus openRawToImagePlus(String filePath, int width, int height, int slices) {
        File file = new File(filePath);
        if (!file.exists()) {
            IJ.error("Arquivo não encontrado: " + filePath);
            return null;
        }

        // Validação de segurança do tamanho do arquivo
        long expectedBytes = (long) width * height * slices * 2; // 2 bytes por voxel (short)
        if (file.length() < expectedBytes) {
            IJ.error("O arquivo é menor do que o esperado para essas dimensões.\n" +
                     "Tamanho esperado: " + expectedBytes + " bytes.\n" +
                     "Tamanho real: " + file.length() + " bytes.");
            return null;
        }

        ImageStack stack = new ImageStack(width, height);

        // Usamos DataInputStream que lê em BIG-ENDIAN por padrão
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            
            for (int z = 0; z < slices; z++) {
                short[] slicePixels = new short[width * height];
                
                // Lê todos os pixels da fatia atual
                for (int i = 0; i < slicePixels.length; i++) {
                    // dis.readShort() lê exatos 2 bytes e junta no formato Big-Endian
                    slicePixels[i] = (short) (dis.readShort());
                }
                
                // Cria um processador de 16-bits (ShortProcessor) para esta fatia
                ShortProcessor sp = new ShortProcessor(width, height, slicePixels, null);
                
                // Adiciona a fatia na pilha (stack)
                stack.addSlice("Slice " + (z + 1), sp);
            }
            
        } catch (IOException e) {
            IJ.handleException(e);
            return null;
        }

        ImagePlus imp = new ImagePlus("BrainWeb Model", stack);
        
        return imp;
    }
    /**
     * Converte um modelo BrainWeb discreto ("crisp") em um fantoma quantitativo de HU.
     * Utiliza FloatProcessor para suportar valores negativos de HU naturalmente.
     * 
     * @param crispModel Imagem original com os rótulos de 0 a 11.
     * @return Novo ImagePlus volumétrico em escala de Hounsfield.
     */
    public static ImagePlus convertBrainWebToHU(ImagePlus crispModel) {
        
        final float[] LUT_HU = {
            -1000f, // 0: Background (Air) IAEA DRP
               15f, // 1: CSF
               35f, // 2: Gray Matter IAEA DRP
               25f, // 3: White Matter IAEA DRP
             -90f, // 4: Fat IAEA DRP
               25f, // 5: Muscle IAEA DRP
               25f, // 6: Muscle/Skin
             1000f, // 7: Skull IAEA DRP
               55f, // 8: Vessels IAEA DRP blood
              -50f, // 9: Around fat
               50f, // 10: Dura mater
              -20f  // 11: Bone marrow
        };

        ImageStack originalStack = crispModel.getStack();
        int width = crispModel.getWidth();
        int height = crispModel.getHeight();
        int slices = originalStack.getSize();

        // Cria uma nova pilha para armazenar os dados em 32-bits (Float)
        ImageStack huStack = new ImageStack(width, height);

        for (int z = 1; z <= slices; z++) {
            ImageProcessor originalProcessor = originalStack.getProcessor(z);
            short[] originalPixels = (short[]) originalProcessor.getPixels();
            
            float[] huPixels = new float[width * height];

            for (int i = 0; i < originalPixels.length; i++) {
                
                int unsignedValue = originalPixels[i] & 0xFFFF;
                
           int label = (int) Math.round((unsignedValue / 65535.0) * 11.0);
                
                if (label >= 0 && label < LUT_HU.length) {
                    huPixels[i] = LUT_HU[label];
                } else {
                    huPixels[i] = -1000f; 
                }
            
            }

            FloatProcessor fp = new FloatProcessor(width, height, huPixels);
            huStack.addSlice("Slice " + z, fp);
        }

        ImagePlus huImage = new ImagePlus(crispModel.getTitle() + " - HU Scale", huStack);    
        
        huImage.getCalibration().setValueUnit("HU");

        return huImage;
    }
    /**
     * Mapeia sufixos típicos do BrainWeb para os HUs de referência.
     */
    private static Map<String, Float> getStandardFuzzyMap() {
        Map<String, Float> map = new HashMap<>();
        map.put("csf", 18f);       // Líquor
        map.put("gm", 30f);       // Substância Cinzenta
        map.put("wm", 25f);       // Substância Branca
        map.put("fat", -90f);      // Gordura
        map.put("muscles", 25f);       // Músculo
        map.put("muscles_skin", 25f);       // Músculo/Pele
        map.put("skull", 1000f);     // Crânio
        map.put("vessels", 50f);       // Vasos
        map.put("fat2", 200f);     // Tecido adiposo misto
        map.put("dura", 800f);      // Dura-máter
        map.put("marrow", 200f);      // Medula óssea
        map.put("bck", -1000f);      // ar
        return map;
    }

    /**
     * @param directoryPath Pasta contendo os arquivos fuzzy descompactados.
     * @param is16Bit true se baixou a versão de 16-bits, false se for a versão de 8-bits.
     */
    public static ImagePlus createFuzzyPhantom(String directoryPath, int width, int height, int slices, boolean is16Bit) {
        Map<String, Float> tissueMap = getStandardFuzzyMap();
        File dir = new File(directoryPath);
        
        if (!dir.exists() || !dir.isDirectory()) {
            IJ.error("Diretório inválido. Forneça a pasta contendo os arquivos do BrainWeb.");
            return null;
        }

        Map<DataInputStream, Float> streams = new HashMap<>();
        
        try {
            for (Map.Entry<String, Float> entry : tissueMap.entrySet()) {
                File tissueFile = findFileWithSuffix(dir, entry.getKey());
                if (tissueFile != null) {
                    DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(tissueFile)));
                    streams.put(dis, entry.getValue());
                } else {
                    IJ.log("Aviso: Arquivo do tecido '" + entry.getKey() + "' não encontrado na pasta.");
                }
            }

            if (streams.isEmpty()) {
                IJ.error("Nenhum arquivo fuzzy reconhecido foi encontrado na pasta.");
                return null;
            }

            ImageStack huStack = new ImageStack(width, height);
            int pixelsPerSlice = width * height;
            
            // A escala máxima usada pelo BrainWeb para representar 100% da fração (1.0)
         // CORREÇÃO: A escala máxima usada pelo BrainWeb (12-bits em arquivo de 16-bits) é 4095
            float maxScale = is16Bit ? 4095.0f : 255.0f; 

            IJ.log("Processando síntese Fuzzy CT com " + streams.size() + " classes de tecido...");

            for (int z = 1; z <= slices; z++) {
                // Acumuladores por voxel:
                //   numerator[i] = Σ_t (fraction_t * HU_t)     -> HU ponderado dos tecidos
                //   weightSum[i] = Σ_t fraction_t              -> fracao total de tecido (nao-ar)
                float[] numerator = new float[pixelsPerSlice];
                float[] weightSum = new float[pixelsPerSlice];
                // (arrays float em Java ja iniciam zerados)

                // Soma as contribuicoes de cada tecido aberto
                for (Map.Entry<DataInputStream, Float> streamEntry : streams.entrySet()) {
                    DataInputStream dis = streamEntry.getKey();
                    float tissueHU = streamEntry.getValue();

                    for (int i = 0; i < pixelsPerSlice; i++) {
                        int val;
                        if (is16Bit) {
                            short raw = dis.readShort();
                            // INVERSAO DE ENDIANNESS: Corrige a ordem dos bytes
                            raw = Short.reverseBytes(raw);
                            val = raw & 0xFFFF; // Garante que o valor seja lido como positivo
                        } else {
                            val = dis.readUnsignedByte();
                        }

                        // Fracao real daquele tecido no voxel
                        float fraction = val / maxScale;

                        // GRAMPO DE SEGURANCA: mantem a fracao em [0, 1]
                        if (fraction > 1.0f) fraction = 1.0f;
                        if (fraction < 0.0f) fraction = 0.0f;

                        if (fraction > 0f) {
                            numerator[i] += fraction * tissueHU;
                            weightSum[i] += fraction;
                        }
                    }
                }

                // Normalizacao: media ponderada VERDADEIRA dos tecidos presentes.
                // Onde nao ha tecido (weightSum ~ 0), o voxel e ar (-1000 HU).
                // Onde ha tecido parcial (Sum f < 1, borda do cranio contra o ar),
                // o restante do voxel e preenchido com ar, evitando sobre/sub-shoot.
                float[] huPixels = new float[pixelsPerSlice];
                for (int i = 0; i < pixelsPerSlice; i++) {
                    float w = weightSum[i];
                    if (w <= 0f) {
                        huPixels[i] = -1000f;                 // voxel totalmente ar
                    } else if (w >= 1f) {
                        huPixels[i] = numerator[i] / w;       // totalmente tecido: media ponderada pura
                    } else {
                        // voxel parcial: mistura tecido (peso w) + ar (peso 1-w)
                        huPixels[i] = numerator[i] + (1f - w) * (-1000f);
                    }
                }

                FloatProcessor fp = new FloatProcessor(width, height, huPixels);
                huStack.addSlice("Slice " + z, fp);
                IJ.showProgress(z, slices);
            }


            // Encerra todos os fluxos de memória de forma segura
            for (DataInputStream dis : streams.keySet()) {
                dis.close();
            }

            ImagePlus huImage = new ImagePlus("BrainWeb Fuzzy Model - HU", huStack);
            huImage.setDisplayRange(-100, 1000);
            huImage.getCalibration().setValueUnit("HU");
            return huImage;

        } catch (IOException e) {
            IJ.handleException(e);
            return null;
        }
    }

    private static File findFileWithSuffix(File dir, String suffix) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().toLowerCase().contains(suffix + "_v.raw") || 
                    f.getName().toLowerCase().contains(suffix + ".raw")) {
                    return f;
                }
            }
        }
        return null;
    }
    /**
     * Redimensiona o volume na direção Z unindo (pela média) fatias adjacentes para simular 
     * espessuras de fatia maiores (ex: 1.0mm, 1.5mm, 2.0mm, 2.5mm).
     * 
     * @param inputImage ImagePlus do volume em escala HU (32-bit Float)
     * @param targetThickness Espessura de fatia desejada em mm (ex: 1.0, 1.5, 2.5)
     * @param nativeThickness Espessura original das fatias em mm (padrão BrainWeb: 0.5mm)
     * @return Novo ImagePlus com as fatias fundidas e calibração espacial atualizada.
     */
    public static ImagePlus resampleSliceThickness(ImagePlus inputImage, double targetThickness, double nativeThickness) {
        if (inputImage == null) return null;
        
        // Se a espessura desejada for menor ou igual à nativa, mantém a resolução original
        if (targetThickness <= nativeThickness) {
            return inputImage;
        }

        ImageStack origStack = inputImage.getStack();
        int width = inputImage.getWidth();
        int height = inputImage.getHeight();
        int origSlices = origStack.getSize();

        // Determina quantas fatias nativas compõem a nova espessura (ex: 2.5 / 0.5 = 5 fatias)
        int slicesToCombine = (int) Math.round(targetThickness / nativeThickness);
        if (slicesToCombine <= 1) {
            return inputImage;
        }

        int newSlicesCount = origSlices / slicesToCombine;
        ImageStack newStack = new ImageStack(width, height);
        int pixelsPerSlice = width * height;

        for (int i = 0; i < newSlicesCount; i++) {
            float[] mergedPixels = new float[pixelsPerSlice];
            int startZ = i * slicesToCombine + 1; // 1-based index no ImageJ
            int actualCombined = 0;

            // Acumula a soma dos valores de HU nas fatias do bloco
            for (int k = 0; k < slicesToCombine; k++) {
                int z = startZ + k;
                if (z > origSlices) break;

                float[] currentSlice = (float[]) origStack.getProcessor(z).getPixels();
                for (int p = 0; p < pixelsPerSlice; p++) {
                    mergedPixels[p] += currentSlice[p];
                }
                actualCombined++;
            }

            // Média simples do bloco de fatias (Volume Parcial em Z)
            if (actualCombined > 0) {
                for (int p = 0; p < pixelsPerSlice; p++) {
                    mergedPixels[p] /= actualCombined;
                }
                FloatProcessor fp = new FloatProcessor(width, height, mergedPixels);
                newStack.addSlice("Slice " + (i + 1) + " (" + targetThickness + "mm)", fp);
            }
        }

        ImagePlus resultImp = new ImagePlus(inputImage.getTitle() + " - " + targetThickness + "mm", newStack);
        
        // Copia e ajusta a calibração de profundidade (Z) do ImageJ
        Calibration cal = inputImage.getCalibration().copy();
        cal.pixelDepth = targetThickness;
        cal.setZUnit("mm");
        resultImp.setCalibration(cal);

        return resultImp;
    }
    }