import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import br.rededor.fisicamedica.SeriesData;
import ij.ImagePlus;
import ij.measure.Calibration;
import ij.plugin.DICOM;
import ij.process.ImageProcessor;

/** Localiza todos os "hotspots" (HU > limiar) numa fatia e reporta sua posicao
 *  relativa ao centro (raio em mm e angulo), agrupando pixels proximos. */
public class AcrHotspotProbe {
    public static void main(String[] args) {
        String dir = args.length > 0 ? args[0] : "/projects/sandbox/testdata/CRANIO S_C";
        int sliceIdx = args.length > 1 ? Integer.parseInt(args[1]) : 8;
        double thr = args.length > 2 ? Double.parseDouble(args[2]) : 300;
        File[] files = new File(dir).listFiles();
        Arrays.sort(files);
        SeriesData sd = new SeriesData("s","u");
        DICOM dcm = new DICOM();
        for (File f : files) sd.addFile(f, dcm.getInfo(f.getAbsolutePath()));
        sd.sortFileMetadataByInstanceNumber();

        File f = sd.getFiles().get(sliceIdx-1);
        ImagePlus imp = new ImagePlus(f.getAbsolutePath());
        ImageProcessor ip = imp.getProcessor();
        Calibration c = imp.getCalibration();
        double px=c.pixelWidth, py=c.pixelHeight; int w=ip.getWidth(),h=ip.getHeight();

        double sx=0,sy=0;long cnt=0;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){double hu=c.getCValue(ip.getf(x,y));if(hu>-200){sx+=x;sy+=y;cnt++;}}
        double cx=sx/cnt, cy=sy/cnt;
        System.out.printf("Fatia %d (%s) centro=(%.1f,%.1f) thr=%.0f%n", sliceIdx, f.getName(), cx, cy, thr);

        // Coleta pixels acima do limiar, dentro de raio 95mm (dentro do phantom)
        List<double[]> pts = new ArrayList<>();
        double maxRpx = 95.0/px;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            double d=Math.hypot(x-cx,y-cy);
            if(d>maxRpx) continue;
            double hu=c.getCValue(ip.getf(x,y));
            if(hu>thr) pts.add(new double[]{x,y,hu});
        }
        System.out.println("pixels acima do limiar: "+pts.size());

        // agrupamento simples por proximidade (8mm)
        double clusterPx = 8.0/px;
        boolean[] used=new boolean[pts.size()];
        int cl=0;
        for(int i=0;i<pts.size();i++){
            if(used[i])continue;
            double ax=pts.get(i)[0],ay=pts.get(i)[1],sumh=0,n=0,gx=0,gy=0;
            for(int j=0;j<pts.size();j++){
                if(used[j])continue;
                if(Math.hypot(pts.get(j)[0]-ax,pts.get(j)[1]-ay)<=clusterPx*3){
                    used[j]=true; gx+=pts.get(j)[0]; gy+=pts.get(j)[1]; sumh+=pts.get(j)[2]; n++;
                }
            }
            gx/=n; gy/=n;
            double rmm=Math.hypot(gx-cx,gy-cy)*px;
            double ang=Math.toDegrees(Math.atan2(gy-cy,gx-cx));
            System.out.printf("  cluster %d: n=%.0f  centro=(%.0f,%.0f)  r=%.1fmm  ang=%.0f  HUmed=%.0f%n",
                    ++cl, n, gx, gy, rmm, ang, sumh/n);
        }
    }
}
