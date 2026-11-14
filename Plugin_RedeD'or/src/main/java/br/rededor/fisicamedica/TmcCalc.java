package br.rededor.fisicamedica;

import java.awt.Rectangle;
import java.io.File;
import java.util.Arrays;
import java.util.List;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Overlay;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ij.gui.Wand;
import ij.measure.Calibration;
import ij.measure.Measurements;
import ij.plugin.filter.EDM;
import ij.plugin.frame.RoiManager;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.FloodFiller;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import io.vacco.savitzkygolay.SgFilter;


public class TmcCalc {
    private ImagePlus imp;
    private ImageProcessor ip;
    private Calibration calibration;
    public double tempAw, tempDw, tempCTDI;
    public double[] Dw, mA, stdev, slice, position;
    SeriesData seriesData;
    
    
    public TmcCalc() { }
    
    public void run() {
            SeriesData activeSeriesData = ImageOpener.activeSeriesData;
            if (activeSeriesData != null) {
                run(activeSeriesData);
                int[] trimIndices = findTrimIndices(Dw);
                int firstNonZero = trimIndices[0];
                int lastNonZero = trimIndices[1];
                
                double[] trimmedSlices = trimArray(slice, firstNonZero, lastNonZero);
                double[] trimmedDw = trimArray(Dw, firstNonZero, lastNonZero);
                double[] trimmedStdev = trimArray(stdev, firstNonZero, lastNonZero);
                double[] trimmedMA = trimArray(mA, firstNonZero, lastNonZero);
                double[] trimmedPos = trimArray(position, firstNonZero, lastNonZero);
                slice=trimmedSlices;
                
                int size = trimmedDw.length;
                float[] fDw = new float[size];
                for(int i=0; i<size; i++) {
                	fDw[i] = (float) trimmedDw[i];
                }
                int w = size/6;
                if(w % 2 == 0) w++;

                SgFilter sg = new SgFilter(w, 0, 2);
    	        float[]h = new float[size];
    	        float[] smoothDw = new float[size];
        		sg.process(fDw, h, smoothDw);
            	for(int k=0;k<size;k++) {
            		Dw[k] = (double) smoothDw[k];
            	}
            //	Dw = trimmedDw;
            	mA=trimmedMA;
            	
            	 size = trimmedStdev.length;
            	 fDw= new float[size];
                 for(int i=0; i<size; i++) {
                 	fDw[i] = (float) trimmedStdev[i];
                 }
                 w = size/20;
                 if(w % 2 == 0) w++;

                sg = new SgFilter(w, 0, 2);
     	        smoothDw = new float[size];
         		sg.process(fDw, h, smoothDw);
             	for(int k=0;k<size;k++) {
             		stdev[k] = (double) smoothDw[k];
             	}
             	
                stdev=trimmedStdev;
                position=trimmedPos;
                stdev=removeOutliers(stdev, 15, 2);
                stdev=removeOutliers(stdev, 15, 2);
                
            } else {
                IJ.error("No active series identified");
                return;
            }
    }

    public void run(SeriesData seriesData) {
        this.seriesData = seriesData;
    	
    	List<File> files = seriesData.getFiles();
         int size = files.size();

        	mA = new double[size];
        	stdev = new double[size];
        	slice = new double[size];
        	position = new double [size];
        	Dw=new double[size];


		for (int i = 0; i < files.size(); i++) {
            File file = files.get(i);
            this.imp = new ImagePlus(file.getAbsolutePath());
            if(imp==null || imp.getWidth()==0) {
              continue;
            }
            this.ip = imp.getProcessor();
           
            this.calibration = imp.getCalibration();

            Object[] segResults = segmenter(i+1);
            double area = (double) segResults[1];
            double mean = (double) segResults[2];
            stdev[i] = (double) segResults[3];
            tempAw = (0.001 * mean + 1) * area;
            tempDw = 2 * Math.sqrt(tempAw / Math.PI) * 0.1;
          
            	String metadata = seriesData.getMetadata(file);           	                
            	String stringMA = DicomProcessor.getTag(metadata, "0018,1151");
            	String stringPosition = DicomProcessor.getTag(metadata, "0020,1041");
            	slice[i] = i+1;
            	if(stringMA!=null)mA[i]=Double.parseDouble(stringMA);
            	if(stringPosition!=null)position[i]=Double.parseDouble(stringPosition);
            	Dw[i] = tempDw;
    }
    }

    public Object[] segmenter(int slicenumber) {
        ByteProcessor Mask = new ByteProcessor(ip.getWidth(), ip.getHeight());
        for (int y = 0; y < ip.getHeight(); y++) {
            for (int x = 0; x < ip.getWidth(); x++) {
                if (ip.getValue(x, y) >= -50) {
                    Mask.putPixel(x, y, 255);
                } else {
                    Mask.putPixel(x, y, 0);
                }
            }
        }
        ByteProcessor filledMask = fillHoles(Mask);
        ByteProcessor erodedMask = erode(filledMask, 10);
         Wand wand = new Wand(erodedMask);

        ShapeRoi finalShape = null;
        PolygonRoi erodedRoi = null;

        for (int y = 0; y < ip.getHeight(); y++) {
            for (int x = 0; x < ip.getWidth(); x++) {
                if (erodedMask.getPixel(x, y) == 255) {
                    wand.autoOutline(x, y, 255, 255, 8 | 256);
                    if (wand.npoints > 0) {
                        PolygonRoi pRoi = new PolygonRoi(wand.xpoints, wand.ypoints, wand.npoints, Roi.FREEROI);
                        erodedRoi=pRoi;
                        PolygonRoi dilatedRoi = dilate(filledMask, pRoi, 10);
                        ip.setRoi(dilatedRoi);
                        ImageStatistics stats = ImageStatistics.getStatistics(ip,0, calibration);
                        double area = stats.area;
                        double mean = stats.mean;
                        double perimeter = dilatedRoi.getLength();
                        double circularity = perimeter == 0.0 ? 0.0 : 4.0 * Math.PI * (stats.pixelCount / (perimeter * perimeter));
                        if(area>2000){

                       if((circularity<0.1 && area<18000) || mean<0) {

                       }else {
                            ShapeRoi shape = new ShapeRoi(dilatedRoi);
                            if (finalShape == null)
                                finalShape = shape;
                            else
                                finalShape.or(shape);
                        }
                        }
                        ip.resetRoi();
                        erodedMask.setValue(0);
                        erodedMask.fill(pRoi);
                    }
                }
            }
        }

        Roi finalRoi = (finalShape != null) ? finalShape : null;
if (finalRoi!=null) {
       
	   ip.setRoi(finalRoi);

       ImageStatistics stats = ImageStatistics.getStatistics(ip, Measurements.SKEWNESS, calibration);
      
       double area = stats.area;
       double mean = stats.mean;
      // ip.setRoi(erodedRoi);
       //stats=ImageStatistics.getStatistics(ip, Measurements.SKEWNESS, calibration);
       double Threshold = autoThreshold(stats.histogram, stats.binSize, calibration, stats.histMin, stats.mean, stats.skewness);
       // System.out.println(slicenumber+";"+Threshold);
        double stdev =  calculateStatisticsWithThreshold(ip, finalRoi, calibration, Threshold) ;
   
        RoiManager roiManager = RoiManager.getInstance();
            if (roiManager == null) {
                roiManager = new RoiManager();
            }
           finalRoi.setPosition(slicenumber);
           roiManager.addRoi(finalRoi);
           roiManager.runCommand("Show All");

        return new Object[]{finalRoi, area, mean, stdev};
        }else {
        	return new Object[]{null, 0.0, 0.0, 0.0};
        }
    }
    
    public ByteProcessor erode(ByteProcessor mask, int radius) {
        ByteProcessor erodedMask = new ByteProcessor(mask.getWidth(), mask.getHeight());
        FloatProcessor floatEdm = new EDM().makeFloatEDM(mask, 0, true);
        float[] fPixels = (float[]) floatEdm.getPixels();
        int width = erodedMask.getWidth();
        int height = erodedMask.getHeight();
        for (int y = 0; y < height; y++) {
        	 for (int x = 0, p = x + y * width; x < width; x++, p++) {
        	        if (fPixels[p] > radius) {
                    erodedMask.putPixel(x, y, 255);
                }else {
                	erodedMask.putPixel(x, y, 0);
                }
            }
        }
        return erodedMask;
    }
    
    public static PolygonRoi dilate(ByteProcessor mask, Roi roi, int radius) {
    	 ByteProcessor dilatedMask = new ByteProcessor(mask.getWidth(), mask.getHeight());
	        dilatedMask.setColor(255); dilatedMask.fill();
	        dilatedMask.setColor(0);dilatedMask.fill(roi);
	      
	        FloatProcessor edm = new EDM().makeFloatEDM(dilatedMask.duplicate(), 0, false);
	        float[] edmPixels = (float[]) edm.getPixels();
	        byte[] dilatedPixels = (byte[]) dilatedMask.getPixels();
	        byte[] originalPixels = (byte[]) mask.getPixels();
	        
	        for (int y = 0; y < mask.getHeight(); y++) {
	            for (int x = 0; x < mask.getWidth(); x++) {
	                int index = y * mask.getWidth() + x;
	                if (edmPixels[index] <= radius && originalPixels[index] == (byte) 255) {
	                    dilatedPixels[index] = (byte) 0;
	                }
	            }
	        }
	        
	        Wand wand = new Wand(dilatedMask);
	        for (int y = 0; y < dilatedMask.getHeight(); y++) {
	            for (int x = 0; x < dilatedMask.getWidth(); x++) {
	                if (dilatedMask.getPixel(x, y) == 0) {
	                    wand.autoOutline(x, y, 0, 0, 8 | 256);
	                    if (wand.npoints > 0) {
	                         roi = new PolygonRoi(wand.xpoints, wand.ypoints, wand.npoints, Roi.FREEROI);
	              	       return (PolygonRoi) roi;
	                    }
	                }
	            }
	        }
	        return null;
    }
    
    public static ByteProcessor fillHoles(ByteProcessor ip) {
        ByteProcessor filledIp = (ByteProcessor) ip.duplicate();

        int width = filledIp.getWidth();
        int height = filledIp.getHeight();
        int background = 0;
        int foreground = 255;
        int tempValue = 127; 
        
        FloodFiller ff = new FloodFiller(filledIp);
        filledIp.setColor(tempValue);

        for (int x = 0; x < width; x++) {
            if (filledIp.getPixel(x, 0) == background) ff.fill(x, 0);
            if (filledIp.getPixel(x, height - 1) == background) ff.fill(x, height - 1);
        }
        for (int y = 0; y < height; y++) {
            if (filledIp.getPixel(0, y) == background) ff.fill(0, y);
            if (filledIp.getPixel(width - 1, y) == background) ff.fill(width - 1, y);
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = filledIp.getPixel(x, y);
                if (pixel == tempValue) {
                    filledIp.putPixel(x, y, background);
                } else if (pixel == background) {
                    filledIp.putPixel(x, y, foreground);
                }
            }
        }
        return filledIp;
    }
    
    public static double[] removeOutliers(double[] series, int window, double zThreshold) {
        int len = series.length;
        double[] result = series.clone();
        int halfWin = window / 2;

        for (int i = 0; i < len; i++) {
            int start = Math.max(0, i - halfWin);
            int end = Math.min(len - 1, i + halfWin);

            // Extrai janela local
            int wSize = end - start + 1;
            double[] windowValues = new double[wSize];
            for (int j = 0; j < wSize; j++) {
                windowValues[j] = series[start + j];
            }

            // Calcula média e desvio padrão da janela
            double mean = mean(windowValues);
            double std = stdDev(windowValues, mean);

            // Verifica se o ponto atual é um outlier
            if (Math.abs(series[i] - mean) > zThreshold * std) {
                result[i] = mean;
            }
        }

        return result;
    }

    private static double mean(double[] values) {
        return Arrays.stream(values).average().orElse(0.0);
    }

    private static double stdDev(double[] values, double mean) {
        return Math.sqrt(Arrays.stream(values).map(v -> (v - mean) * (v - mean)).average().orElse(0.0));
    }
    // Método para encontrar os índices de início e fim não-zero
    public static int[] findTrimIndices(double[] array) {
        int firstNonZero = 0;
        int lastNonZero = array.length - 1;
        
        // Encontrar primeiro não-zero
        while (firstNonZero < array.length && array[firstNonZero] == 0) {
            firstNonZero++;
        }
        
        // Encontrar último não-zero
        while (lastNonZero >= 0 && array[lastNonZero] == 0) {
            lastNonZero--;
        }
        
        // Verificar se todos são zeros
        if (firstNonZero > lastNonZero) {
            return new int[]{0, 0}; // Retorna array vazio
        }
        
        return new int[]{firstNonZero, lastNonZero};
    }
    
    public static int[] trimArray(int[] array, int start, int end) {
        return Arrays.copyOfRange(array, start, end + 1);
    }
    
    public static double[] trimArray(double[] array, int start, int end) {
        return Arrays.copyOfRange(array, start, end + 1);
    }
   

    public static double calculateStatisticsWithThreshold(
            ImageProcessor ip, Roi roi, Calibration cal, double thresholdHU) {
        
        Rectangle bounds = roi.getBounds();
        int centerY = bounds.y + bounds.height / 2;
        int centerX = bounds.x + bounds.width / 2;
        double maxDistanceInPixels = 35 / cal.pixelHeight;
        
        int yStart = (int) Math.max(bounds.y, centerY - maxDistanceInPixels);
        int yEnd = (int) Math.min(bounds.y + bounds.height - 1, centerY + maxDistanceInPixels);
        
        int xStart = (int) Math.max(bounds.x, centerX - maxDistanceInPixels);
        int xEnd = (int) Math.min(bounds.x + bounds.width - 1, centerX + maxDistanceInPixels);
        
        double sum = 0.0;
        double sumSquares = 0.0;
        int count = 0;
        
        for (int y = yStart; y <= yEnd; y++) {
            for (int x = xStart; x <= xEnd ; x++) {
                    double value = ip.getValue(x, y);
                    if (value > thresholdHU) {
                        sum += value;
                        sumSquares += value * value;
                        count++;
                }
            }
        }
        
        if (count < 2) return 0.0;
        double variance = (sumSquares - (sum * sum) / count) / (count - 1);
        return Math.sqrt(variance);
    }
    public static double autoThreshold(int[] histograma, double binSize, Calibration cal, double histMin, double mean, double skewness) {
    	if(cal.getCValue(histMin)>0)return 0;
    	int moda = 0;
        int pico = histograma[0];
        for (int i = 1; i < histograma.length; i++) {
            if (histograma[i] > pico) {
                pico = histograma[i];
                moda = i;
            }
        }
        int limiar = (int)(pico * 0.05);      
       
       
        int limiteDireita = moda;
        for (int i = moda; i < histograma.length; i++) {
            if (histograma[i] <= limiar) {
                limiteDireita = i;
                break;
            }
        }
        int delta = limiteDireita-moda;
        int t=0;
        for (int i = 1; i < histograma.length; i++) {
            if (histograma[i] > 30) {
            	t=i;
             //  System.out.println(cal.getCValue(histMin + i * binSize + binSize / 2.0));
                break;
            }
        }        
        double value =  cal.getCValue(histMin + (moda-delta) * binSize + binSize / 2.0);
        if(cal.getCValue(histMin + t* binSize + binSize / 2.0)>=0)value-=30;
        else if(cal.getCValue(histMin + t* binSize + binSize / 2.0)>=-20)value-=20;
        else if(cal.getCValue(histMin + t* binSize + binSize / 2.0)>=-50)value-=10;

        return value;
        
    }

    }