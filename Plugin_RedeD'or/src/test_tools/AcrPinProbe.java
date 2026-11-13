import java.io.File;
import java.util.Arrays;

import br.rededor.fisicamedica.SeriesData;
import ij.ImagePlus;
import ij.measure.Calibration;
import ij.plugin.DICOM;
import ij.process.ImageProcessor;

/** Sonda a fatia de uniformidade real: acha o centroide do phantom, mede HU fora
 *  do phantom (varios cantos) e procura estruturas de alto HU (pinos) ao longo
 *  de um anel a ~50mm do centro, reportando angulos/HU para entender a geometria. */
public class AcrPinProbe {
    public static void main(String[] args) {
        String dir = args.length > 0 ? args[0] : "/projects/sandbox/testdata/CRANIO S_C";
        int sliceIdx = args.length > 1 ? Integer.parseInt(args[1]) : 24; // loc -15
        File[] files = new File(dir).listFiles();
        Arrays.sort(files);
        SeriesData sd = new SeriesData("CRANIO S/C", "uid");
        DICOM dcm = new DICOM();
        for (File f : files) sd.addFile(f, dcm.getInfo(f.getAbsolutePath()));
        sd.sortFileMetadataByInstanceNumber();

        File f = sd.getFiles().get(sliceIdx - 1);
        ImagePlus imp = new ImagePlus(f.getAbsolutePath());
        ImageProcessor ip = imp.getProcessor();
        Calibration c = imp.getCalibration();
        double px = c.pixelWidth, py = c.pixelHeight;
        int w = ip.getWidth(), h = ip.getHeight();

        // centroide do corpo (HU>-200)
        double sx=0,sy=0; long cnt=0;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){double hu=c.getCValue(ip.getf(x,y));if(hu>-200){sx+=x;sy+=y;cnt++;}}
        double cx=sx/cnt, cy=sy/cnt;
        System.out.printf("Fatia %d (%s): centro=(%.1f,%.1f) px%n", sliceIdx, f.getName(), cx, cy);

        // HU fora do phantom: 4 cantos
        System.out.println("HU nos cantos (fora do phantom):");
        probeCorner(ip,c,"TL",10,10);
        probeCorner(ip,c,"TR",w-30,10);
        probeCorner(ip,c,"BL",10,h-30);
        probeCorner(ip,c,"BR",w-30,h-30);

        // Varre anel a 50mm procurando maximos de HU
        double ringMm = 50.0;
        double ringPx = ringMm/px;
        System.out.println("\nVarredura do anel a 50mm (HU > 200 destacados):");
        double maxHU=-1e9; double maxAng=0;
        for(int deg=0; deg<360; deg+=5){
            double a=Math.toRadians(deg);
            int x=(int)Math.round(cx+ringPx*Math.cos(a));
            int y=(int)Math.round(cy+ringPx*Math.sin(a));
            if(x<0||y<0||x>=w||y>=h) continue;
            double hu=c.getCValue(ip.getf(x,y));
            if(hu>maxHU){maxHU=hu;maxAng=deg;}
            if(hu>200) System.out.printf("  %3d deg -> HU=%.0f%n", deg, hu);
        }
        System.out.printf("Maior HU no anel: %.0f @ %.0f deg%n", maxHU, maxAng);
    }

    static void probeCorner(ImageProcessor ip, Calibration c, String name, int x0, int y0){
        double sum=0;int n=0;
        for(int y=y0;y<y0+20;y++)for(int x=x0;x<x0+20;x++){
            if(x<0||y<0||x>=ip.getWidth()||y>=ip.getHeight())continue;
            sum+=c.getCValue(ip.getf(x,y));n++;
        }
        System.out.printf("  %s @(%d,%d): media HU=%.0f (n=%d)%n", name, x0,y0, n>0?sum/n:Double.NaN, n);
    }
}
