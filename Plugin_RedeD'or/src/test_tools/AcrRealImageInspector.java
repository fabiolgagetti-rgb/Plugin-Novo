import java.io.File;
import java.util.Arrays;

import ij.ImagePlus;
import ij.measure.Calibration;
import ij.plugin.DICOM;
import ij.process.ImageProcessor;

/**
 * Inspetor standalone (fora do pacote do plugin) para examinar a serie real
 * CRANIO S_C: imprime tags DICOM relevantes, calibracao e, para cada fatia,
 * min/max HU dentro de um ROI central -- util para entender a geometria do
 * phantom antes/depois de rodar o motor de CQ.
 *
 * Nao faz parte do plugin; roda via: java -cp <classes>:<deps> AcrRealImageInspector <dir>
 */
public class AcrRealImageInspector {

    public static void main(String[] args) {
        String dir = args.length > 0 ? args[0] : "/projects/sandbox/testdata/CRANIO S_C";
        File d = new File(dir);
        File[] files = d.listFiles();
        if (files == null) { System.out.println("Dir nao encontrado: " + dir); return; }
        Arrays.sort(files);

        System.out.println("=== Serie: " + dir + " (" + files.length + " arquivos) ===");

        // Metadados da primeira fatia
        DICOM dcm = new DICOM();
        String meta = dcm.getInfo(files[0].getAbsolutePath());
        printTag(meta, "0010,0010", "Paciente");
        printTag(meta, "0008,103E", "Serie");
        printTag(meta, "0018,1030", "Protocolo");
        printTag(meta, "0018,1210", "Kernel");
        printTag(meta, "0018,0050", "Espessura corte (mm)");
        printTag(meta, "0018,0088", "Spacing between slices (mm)");
        printTag(meta, "0018,0060", "kVp");
        printTag(meta, "0018,1151", "mA");
        printTag(meta, "0018,1152", "Exposure (mAs)");
        printTag(meta, "0018,9305", "Revolution time");
        printTag(meta, "0018,1150", "Exposure time");
        printTag(meta, "0018,9311", "Pitch");
        printTag(meta, "0028,0030", "Pixel spacing");
        printTag(meta, "0020,0013", "Instance number");
        printTag(meta, "0020,1041", "Slice location");

        ImagePlus imp0 = new ImagePlus(files[0].getAbsolutePath());
        Calibration cal = imp0.getCalibration();
        System.out.printf("%nCalibracao: pw=%.4f ph=%.4f unit=%s  dim=%dx%d%n",
                cal.pixelWidth, cal.pixelHeight, cal.getUnit(), imp0.getWidth(), imp0.getHeight());

        // Perfil longitudinal: area do corpo (HU>-500) e min/max HU central por fatia
        double px = cal.pixelWidth > 0 ? cal.pixelWidth : 0.5;
        double py = cal.pixelHeight > 0 ? cal.pixelHeight : 0.5;
        double pixelArea = px * py;

        System.out.println("\nidx  file     areaBody(mm2)  minHU_c  maxHU_c  loc");
        for (int i = 0; i < files.length; i++) {
            ImagePlus imp = new ImagePlus(files[i].getAbsolutePath());
            ImageProcessor ip = imp.getProcessor();
            Calibration c = imp.getCalibration();
            int w = ip.getWidth(), h = ip.getHeight();

            int count = 0;
            int n = ip.getPixelCount();
            for (int p = 0; p < n; p++) {
                double hu = c.getCValue(ip.getf(p));
                if (hu > -500) count++;
            }
            double areaMM2 = count * pixelArea;

            // ROI central 140mm diametro
            double rx = 70.0 / px, ry = 70.0 / py;
            double cx = w / 2.0, cy = h / 2.0;
            double minHU = Double.MAX_VALUE, maxHU = -Double.MAX_VALUE;
            for (int y = (int)(cy-ry); y < cy+ry; y++)
                for (int x = (int)(cx-rx); x < cx+rx; x++) {
                    if (x<0||y<0||x>=w||y>=h) continue;
                    if (Math.hypot(x-cx,y-cy) > rx) continue;
                    double hu = c.getCValue(ip.getf(x,y));
                    if (hu<minHU) minHU=hu;
                    if (hu>maxHU) maxHU=hu;
                }
            String loc = getTag(imp.getProperty("Info")!=null?imp.getProperty("Info").toString():"", "0020,1041");
            System.out.printf("%2d  %-8s  %10.0f   %6.0f   %6.0f   %s%n",
                    i+1, files[i].getName(), areaMM2, minHU, maxHU, loc==null?"-":loc);
        }
    }

    static void printTag(String meta, String tag, String label) {
        System.out.println(label + " [" + tag + "]: " + getTag(meta, tag));
    }

    // copia simplificada de DicomProcessor.getTag (que e package-private ao plugin)
    static String getTag(String metadata, String tag) {
        if (metadata == null) return null;
        int index1 = metadata.indexOf(tag);
        if (index1 == -1) return null;
        if (index1 + 11 < metadata.length() && metadata.charAt(index1 + 11) == '>') {
            index1 = metadata.indexOf(tag, index1 + 10);
            if (index1 == -1) return null;
        }
        index1 = metadata.indexOf(":", index1);
        if (index1 == -1) return null;
        int index2 = metadata.indexOf("\n", index1);
        if (index2 == -1) return null;
        return metadata.substring(index1 + 1, index2).trim();
    }
}
