import java.io.File;
import java.util.Arrays;

import br.rededor.fisicamedica.SeriesData;
import ij.ImagePlus;
import ij.gui.OvalRoi;
import ij.measure.Calibration;
import ij.measure.Measurements;
import ij.plugin.DICOM;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;

/** Mede HU/ruido central das fatias uniformes (13..27) para achar a agua pura. */
public class AcrUniformProbe {
    public static void main(String[] args) {
        String dir = args.length > 0 ? args[0] : "/projects/sandbox/testdata/CRANIO S_C";
        File[] files = new File(dir).listFiles();
        Arrays.sort(files);
        SeriesData sd = new SeriesData("s","u");
        DICOM dcm = new DICOM();
        for (File f : files) sd.addFile(f, dcm.getInfo(f.getAbsolutePath()));
        sd.sortFileMetadataByInstanceNumber();

        System.out.println("fatia  loc      centroHU  sd   maxHU_central");
        for (int z = 13; z <= 27; z++) {
            File f = sd.getFiles().get(z-1);
            String loc = tag(sd.getMetadata(f), "0020,1041");
            ImagePlus imp = new ImagePlus(f.getAbsolutePath());
            ImageProcessor ip = imp.getProcessor();
            Calibration c = imp.getCalibration();
            double px=c.pixelWidth; int w=ip.getWidth(),h=ip.getHeight();
            // centroide
            double sx=0,sy=0;long n=0;
            for(int y=0;y<h;y++)for(int x=0;x<w;x++){double hu=c.getCValue(ip.getf(x,y));if(hu>-200){sx+=x;sy+=y;n++;}}
            double cx=sx/n,cy=sy/n;
            double r=10.0/px;
            ip.setRoi(new OvalRoi(cx-r,cy-r,r*2,r*2));
            ImageStatistics st=ImageStatistics.getStatistics(ip, Measurements.MEAN+Measurements.STD_DEV, c);
            ip.resetRoi();
            // max HU num ROI de 70mm (detecta estruturas de baixo contraste)
            double r2=70.0/px; double mx=-1e9;
            for(int y=(int)(cy-r2);y<cy+r2;y++)for(int x=(int)(cx-r2);x<cx+r2;x++){
                if(x<0||y<0||x>=w||y>=h)continue; if(Math.hypot(x-cx,y-cy)>r2)continue;
                double hu=c.getCValue(ip.getf(x,y)); if(hu>mx)mx=hu;
            }
            System.out.printf("%2d    %7s   %7.2f  %5.2f  %6.0f%n", z, loc, st.mean, st.stdDev, mx);
        }
    }
    static String tag(String m,String t){
        if(m==null)return null;int i=m.indexOf(t);if(i==-1)return null;
        if(i+11<m.length()&&m.charAt(i+11)=='>'){i=m.indexOf(t,i+10);if(i==-1)return null;}
        i=m.indexOf(":",i);if(i==-1)return null;int j=m.indexOf("\n",i);if(j==-1)return null;
        return m.substring(i+1,j).trim();
    }
}
