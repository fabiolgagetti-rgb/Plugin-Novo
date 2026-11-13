import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import br.rededor.fisicamedica.SeriesData;
import ij.ImagePlus;
import ij.measure.Calibration;
import ij.plugin.DICOM;
import ij.process.ImageProcessor;

/** Procura, em cada fatia da regiao homogenea, PARES de pontos claros
 *  diametralmente opostos cuja distancia esteja proxima de 100mm. */
public class AcrPin100Probe {
    public static void main(String[] args) {
        String dir = args.length>0?args[0]:"/projects/sandbox/testdata/CRANIO S_C";
        File[] files = new File(dir).listFiles();
        Arrays.sort(files);
        SeriesData sd = new SeriesData("s","u");
        DICOM dcm = new DICOM();
        for (File f: files) sd.addFile(f, dcm.getInfo(f.getAbsolutePath()));
        sd.sortFileMetadataByInstanceNumber();

        int from = args.length>1?Integer.parseInt(args[1]):13;
        int to   = args.length>2?Integer.parseInt(args[2]):27;
        double thr = args.length>3?Double.parseDouble(args[3]):40;

        for (int z=from; z<=to; z++) {
            File f = sd.getFiles().get(z-1);
            ImagePlus imp = new ImagePlus(f.getAbsolutePath());
            ImageProcessor ip = imp.getProcessor();
            Calibration c = imp.getCalibration();
            double px=c.pixelWidth; int w=ip.getWidth(),h=ip.getHeight();
            double sx=0,sy=0;long n=0;
            for(int y=0;y<h;y++)for(int x=0;x<w;x++){double hu=c.getCValue(ip.getf(x,y));if(hu>-200){sx+=x;sy+=y;n++;}}
            double cx=sx/n,cy=sy/n;

            // coleta pontos claros entre 30 e 80mm do centro
            List<double[]> pts=new ArrayList<>();
            double rIn=30.0/px,rOut=85.0/px;
            for(int y=0;y<h;y++)for(int x=0;x<w;x++){
                double d=Math.hypot(x-cx,y-cy); if(d<rIn||d>rOut)continue;
                double hu=c.getCValue(ip.getf(x,y)); if(hu>thr) pts.add(new double[]{x,y,hu});
            }
            if(pts.isEmpty())continue;
            // cluster
            double cl=8.0/px; boolean[] used=new boolean[pts.size()];
            List<double[]> cs=new ArrayList<>();
            for(int i=0;i<pts.size();i++){ if(used[i])continue;
                double ax=pts.get(i)[0],ay=pts.get(i)[1],gx=0,gy=0,sh=0;int k=0;
                for(int j=0;j<pts.size();j++){if(used[j])continue;
                    if(Math.hypot(pts.get(j)[0]-ax,pts.get(j)[1]-ay)<=cl*2){used[j]=true;gx+=pts.get(j)[0];gy+=pts.get(j)[1];sh+=pts.get(j)[2];k++;}}
                if(k>=3) cs.add(new double[]{gx/k,gy/k,sh/k,k});
            }
            // procura par oposto ~100mm
            for(int i=0;i<cs.size();i++)for(int j=i+1;j<cs.size();j++){
                double mx=(cs.get(i)[0]+cs.get(j)[0])/2,my=(cs.get(i)[1]+cs.get(j)[1])/2;
                double off=Math.hypot(mx-cx,my-cy)*px;
                double dist=Math.hypot((cs.get(j)[0]-cs.get(i)[0])*px,(cs.get(j)[1]-cs.get(i)[1])*px);
                if(off<12 && dist>80 && dist<120){
                    double ang=Math.toDegrees(Math.atan2(Math.abs((cs.get(j)[1]-cs.get(i)[1])),Math.abs((cs.get(j)[0]-cs.get(i)[0]))));
                    System.out.printf("fatia %2d: PAR dist=%.1fmm off=%.1fmm ang=%.0f HUa=%.0f HUb=%.0f%n",
                        z, dist, off, ang, cs.get(i)[2], cs.get(j)[2]);
                }
            }
        }
        System.out.println("(fim)");
    }
}
