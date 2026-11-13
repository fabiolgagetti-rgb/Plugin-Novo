import java.io.File;
import java.util.Arrays;
import java.util.List;

import br.rededor.fisicamedica.SeriesData;
import br.rededor.fisicamedica.Segmenter;
import ij.ImagePlus;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.measure.Measurements;
import ij.plugin.DICOM;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;

/** Diagnostica a colocacao dos ROIs de NPS numa fatia uniforme: imprime o
 *  tamanho em px, o raio de distribuicao e o std-dev de cada ROI (para detectar
 *  ROIs que saem do phantom e inflam a variancia). */
public class AcrNpsProbe {
    public static void main(String[] args) {
        String dir = args.length>0?args[0]:"/projects/sandbox/testdata/CRANIO S_C";
        int slice = args.length>1?Integer.parseInt(args[1]):16;
        double roiMm = args.length>2?Double.parseDouble(args[2]):80.0;
        double distMm = args.length>3?Double.parseDouble(args[3]):80.0;
        int nRois = args.length>4?Integer.parseInt(args[4]):12;

        File[] files = new File(dir).listFiles();
        Arrays.sort(files);
        SeriesData sd = new SeriesData("s","u");
        DICOM dcm = new DICOM();
        for (File f: files) sd.addFile(f, dcm.getInfo(f.getAbsolutePath()));
        sd.sortFileMetadataByInstanceNumber();

        File f = sd.getFiles().get(slice-1);
        ImagePlus imp = new ImagePlus(f.getAbsolutePath());
        ImageProcessor ip = imp.getProcessor();
        Calibration c = imp.getCalibration();
        double px=c.pixelWidth;
        int roiPx=(int)Math.round(roiMm/px); if(roiPx%2!=0)roiPx++;
        double distPx=distMm/px;
        System.out.printf("Fatia %d: px=%.4fmm | ROI=%dpx (%.0fmm) | distRaio=%.0fpx (%.0fmm) | phantom raio ~%.0fpx%n",
                slice, px, roiPx, roiMm, distPx, distMm, 100.0/px);

        PolygonRoi phantom = Segmenter.segmenter(ip, c, -200, 5000, 0.2, false, 0, 20);
        if(phantom==null){System.out.println("phantom nao segmentado");return;}
        List<Roi> recs = Segmenter.createCircularRecordRois(ip, c, phantom, nRois, roiPx, distPx, false, slice);
        System.out.println("ROIs gerados: "+recs.size());
        int i=0;
        for(Roi r: recs){
            java.awt.Rectangle b=r.getBounds();
            ip.setRoi(r);
            ImageProcessor crop=ip.crop();
            ip.resetRoi();
            ImageStatistics st=ImageStatistics.getStatistics(crop, Measurements.MEAN+Measurements.STD_DEV+Measurements.MIN_MAX, c);
            System.out.printf("  ROI %2d: bounds=(%d,%d %dx%d) mean=%.1f sd=%.1f min=%.0f max=%.0f%n",
                    ++i, b.x,b.y,b.width,b.height, st.mean, st.stdDev, c.getCValue(st.min), c.getCValue(st.max));
        }
    }
}
