import java.io.File;
import java.util.Arrays;
import java.lang.reflect.Method;

import br.rededor.fisicamedica.SeriesData;
import ij.ImageStack;
import ij.ImagePlus;
import ij.measure.Calibration;
import ij.plugin.DICOM;

/** Imprime a classificacao de cada fatia (via reflexao em classifySlice) para
 *  depurar o mapa de modulos. */
public class AcrClassify {
    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : "/projects/sandbox/testdata/CRANIO S_C";
        File[] files = new File(dir).listFiles();
        Arrays.sort(files);
        SeriesData sd = new SeriesData("s","u");
        DICOM dcm = new DICOM();
        for (File f : files) sd.addFile(f, dcm.getInfo(f.getAbsolutePath()));
        sd.sortFileMetadataByInstanceNumber();

        ImageStack stack = new ImageStack();
        Calibration cal = null;
        for (File f : sd.getFiles()) {
            ImagePlus imp = new ImagePlus(f.getAbsolutePath());
            if (cal == null) cal = imp.getCalibration();
            stack.addSlice(imp.getProcessor());
        }
        double px = cal.pixelWidth, py = cal.pixelHeight;

        Class<?> cls = Class.forName("br.rededor.fisicamedica.AcrQcAnalyser");
        Method classify = cls.getDeclaredMethod("classifySlice", ImageStack.class, int.class, Calibration.class, double.class, double.class);
        classify.setAccessible(true);

        String loc0 = null;
        System.out.println("fatia  loc      classe");
        for (int z = 1; z <= stack.getSize(); z++) {
            String meta = sd.getMetadata(sd.getFiles().get(z-1));
            String loc = tag(meta, "0020,1041");
            Object kind = classify.invoke(null, stack, z, cal, px, py);
            System.out.printf("%2d    %7s   %s%n", z, loc, kind);
        }
    }
    static String tag(String m,String t){
        if(m==null)return null;int i=m.indexOf(t);if(i==-1)return null;
        if(i+11<m.length()&&m.charAt(i+11)=='>'){i=m.indexOf(t,i+10);if(i==-1)return null;}
        i=m.indexOf(":",i);if(i==-1)return null;int j=m.indexOf("\n",i);if(j==-1)return null;
        return m.substring(i+1,j).trim();
    }
}
