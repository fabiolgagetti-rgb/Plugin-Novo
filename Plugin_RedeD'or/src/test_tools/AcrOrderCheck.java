import java.io.File;
import java.util.Arrays;

import br.rededor.fisicamedica.SeriesData;
import ij.ImagePlus;
import ij.measure.Calibration;
import ij.plugin.DICOM;
import ij.process.ImageProcessor;

/** Imprime, na ordem de analise (apos sort por InstanceNumber), o Slice Location
 *  e min/max HU central de cada fatia -- para checar se a ordem e monotonica. */
public class AcrOrderCheck {
    public static void main(String[] args) {
        String dir = args.length > 0 ? args[0] : "/projects/sandbox/testdata/CRANIO S_C";
        File[] files = new File(dir).listFiles();
        Arrays.sort(files);
        SeriesData sd = new SeriesData("CRANIO S/C", "uid");
        DICOM dcm = new DICOM();
        for (File f : files) sd.addFile(f, dcm.getInfo(f.getAbsolutePath()));
        sd.sortFileMetadataByInstanceNumber();

        System.out.println("idx file   instNo  loc     minHU_c  maxHU_c");
        int idx = 1;
        for (File f : sd.getFiles()) {
            String meta = sd.getMetadata(f);
            String inst = tag(meta, "0020,0013");
            String loc = tag(meta, "0020,1041");
            ImagePlus imp = new ImagePlus(f.getAbsolutePath());
            ImageProcessor ip = imp.getProcessor();
            Calibration c = imp.getCalibration();
            double px = c.pixelWidth>0?c.pixelWidth:0.5;
            int w=ip.getWidth(),h=ip.getHeight();
            double rx=70.0/px, cx=w/2.0, cy=h/2.0;
            double mn=1e9,mx=-1e9;
            for (int y=(int)(cy-rx);y<cy+rx;y++) for(int x=(int)(cx-rx);x<cx+rx;x++){
                if(x<0||y<0||x>=w||y>=h) continue;
                if(Math.hypot(x-cx,y-cy)>rx) continue;
                double hu=c.getCValue(ip.getf(x,y));
                if(hu<mn)mn=hu; if(hu>mx)mx=hu;
            }
            System.out.printf("%2d  %-7s %4s  %7s  %6.0f  %6.0f%n", idx++, f.getName(), inst, loc, mn, mx);
        }
    }
    static String tag(String m,String t){
        if(m==null)return null; int i=m.indexOf(t); if(i==-1)return null;
        if(i+11<m.length()&&m.charAt(i+11)=='>'){i=m.indexOf(t,i+10);if(i==-1)return null;}
        i=m.indexOf(":",i); if(i==-1)return null; int j=m.indexOf("\n",i); if(j==-1)return null;
        return m.substring(i+1,j).trim();
    }
}
