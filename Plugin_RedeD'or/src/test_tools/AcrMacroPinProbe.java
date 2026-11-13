import java.io.File;
import java.util.Arrays;

import br.rededor.fisicamedica.SeriesData;
import ij.ImagePlus;
import ij.measure.Calibration;
import ij.plugin.DICOM;
import ij.process.ImageProcessor;

/** Reproduz a logica da macro para os pinos t1/t2 no modulo de inserts:
 *  posicoes de referencia em mm ajustadas pelo offset do centro detectado,
 *  com busca local de maximo (5mm). Testa em varias fatias do Modulo 1. */
public class AcrMacroPinProbe {
    // Coordenadas de referencia da macro (mm)
    static final double refCenter3X=125.051, refCenter3Y=124.801;
    static final double t1X=139.16, t1Y=110.35;
    static final double t2X=68.36,  t2Y=180.66;

    public static void main(String[] args) {
        String dir = args.length>0?args[0]:"/projects/sandbox/testdata/CRANIO S_C";
        File[] files = new File(dir).listFiles();
        Arrays.sort(files);
        SeriesData sd = new SeriesData("s","u");
        DICOM dcm = new DICOM();
        for (File f: files) sd.addFile(f, dcm.getInfo(f.getAbsolutePath()));
        sd.sortFileMetadataByInstanceNumber();

        double nominal = Math.hypot(t1X-t2X, t1Y-t2Y);
        System.out.printf("Distancia nominal t1-t2 (ref macro) = %.2f mm%n%n", nominal);

        int zfrom = args.length>1?Integer.parseInt(args[1]):29;
        int zto   = args.length>2?Integer.parseInt(args[2]):35;
        for (int z=zfrom; z<=zto; z++) {
            File f = sd.getFiles().get(z-1);
            ImagePlus imp = new ImagePlus(f.getAbsolutePath());
            ImageProcessor ip = imp.getProcessor();
            Calibration c = imp.getCalibration();
            double pw=c.pixelWidth, ph=c.pixelHeight;
            int w=ip.getWidth(),h=ip.getHeight();

            // centro detectado (centroide HU>-200) em mm
            double sx=0,sy=0;long n=0;
            for(int y=0;y<h;y++)for(int x=0;x<w;x++){double hu=c.getCValue(ip.getf(x,y));if(hu>-200){sx+=x;sy+=y;n++;}}
            double cxPx=sx/n, cyPx=sy/n;
            double cxMm=cxPx*pw, cyMm=cyPx*ph;

            // offset entre centro detectado e refCenter3
            double offX = cxMm - refCenter3X;
            double offY = cyMm - refCenter3Y;

            double[] m1 = localMax(ip,c,pw,ph, (t1X+offX)/pw, (t1Y+offY)/ph, 5.0/pw);
            double[] m2 = localMax(ip,c,pw,ph, (t2X+offX)/pw, (t2Y+offY)/ph, 5.0/pw);
            double dist = Math.hypot((m2[0]-m1[0])*pw,(m2[1]-m1[1])*ph);
            System.out.printf("fatia %2d: m1=(%.0f,%.0f)HU=%.0f  m2=(%.0f,%.0f)HU=%.0f  dist=%.2fmm%n",
                z, m1[0],m1[1],m1[2], m2[0],m2[1],m2[2], dist);
        }
    }

    static double[] localMax(ImageProcessor ip, Calibration c, double pw, double ph, double gx, double gy, double sr){
        int cx=(int)Math.round(gx), cy=(int)Math.round(gy), s=(int)Math.round(sr);
        double best=-1e9; int bx=cx,by=cy;
        for(int y=cy-s;y<=cy+s;y++)for(int x=cx-s;x<=cx+s;x++){
            if(x<0||y<0||x>=ip.getWidth()||y>=ip.getHeight())continue;
            double hu=c.getCValue(ip.getf(x,y)); if(hu>best){best=hu;bx=x;by=y;}
        }
        return new double[]{bx,by,best};
    }
}
