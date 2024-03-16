///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS net.lingala.zip4j:zip4j:2.11.5 

import static java.lang.System.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.lingala.zip4j.ZipFile;

public class preprocess {


    static final String CSV_FILE_NAME = "ch.swisstopo.swisssurface3d-25fwj1Iq.csv";
    static final String TMP_DIRECTORY = "/tmp/";
    static final String ZIP_DIRECTORY = System.getProperty("user.home")+"/tmp/zip";
    static final String LAZ_DIRECTORY = System.getProperty("user.home")+"/tmp/laz";
    static final String DSM_DIRECTORY = System.getProperty("user.home")+"/tmp/dsm";
    static final String DSM_SHADED_RELIEF_DIRECTORY = System.getProperty("user.home")+"/tmp/dsm_shaded_relief";
    static final String DTM_DIRECTORY = System.getProperty("user.home")+"/tmp/dtm";
    static final String DTM_SHADED_RELIEF_DIRECTORY = System.getProperty("user.home")+"/tmp/dtm_shaded_relief";
    static final String NDSM_BUILDINGS_DIRECTORY = System.getProperty("user.home")+"/tmp/ndsm_buildings";
    static final String NDSM_VEGETATION_DIRECTORY = System.getProperty("user.home")+"/tmp/ndsm_vegetation";


    public static void main(String... args) throws IOException, URISyntaxException {
        List<String> fileLocations;
        try (Stream<String> lines = Files.lines(Paths.get(CSV_FILE_NAME))) {
            fileLocations = lines.collect(Collectors.toList());
        }

        int maxThreads = 24; 
        ExecutorService executor = Executors.newFixedThreadPool(maxThreads);

        for (String fileLocation : fileLocations) {
            if (fileLocation.length() == 0 || fileLocation.startsWith("#")) {
                continue;
            }

            executor.execute(() -> {
                try {
                    makeitso(fileLocation);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            

        }

        executor.shutdown();
    }

    private static void makeitso(String fileLocation) throws IOException, URISyntaxException {
        String fileName = fileLocation.substring(fileLocation.lastIndexOf("/")+1);
        String lasFileName = fileName.substring(20,29).replace("-", "_")+".las";
        err.println("-- Download: " + fileLocation);
        URI uri = new URI(fileLocation);
        File zipFile = Paths.get(ZIP_DIRECTORY).resolve(fileName).toFile();
        ReadableByteChannel readableByteChannel = Channels.newChannel(uri.toURL().openStream());
        try(FileOutputStream fileOutputStream = new FileOutputStream(zipFile);) {
            FileChannel fileChannel = fileOutputStream.getChannel();
            fileChannel.transferFrom(readableByteChannel, 0, Long.MAX_VALUE);    
        }
        
        err.println("-- Unzip: " + zipFile);
        new ZipFile(zipFile).extractFile(lasFileName, Paths.get(ZIP_DIRECTORY).toString());

        String lasFile = Paths.get(ZIP_DIRECTORY, lasFileName).toFile().getAbsolutePath();
        String lazFile = Paths.get(LAZ_DIRECTORY, lasFileName.replace(".las", ".laz")).toFile().getAbsolutePath();
        err.println("-- las2laz: " + lasFile);
        try {
            String cmd = "pdal pipeline las2laz.json --readers.las.filename="+lasFile+" --writers.las.filename="+lazFile;
            err.println(cmd);
            ProcessBuilder pb = new ProcessBuilder(cmd.split(" "));
            Process p = pb.start();
            {
                BufferedReader is = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line = null;
                while ((line = is.readLine()) != null)
                    err.println(line);
                p.waitFor();
            }
            
            if (p.exitValue() != 0) {
                err.println("Error while processing: " + lasFile);
            }
        } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                err.println(e.getMessage());
        }

        String dsmOrigFile = Paths.get(DSM_DIRECTORY, "orig_"+lasFileName.replace(".las", ".tif")).toFile().getAbsolutePath();
        Double minE = Double.valueOf(lasFileName.substring(0, 4) + "000");
        Double minN = Double.valueOf(lasFileName.substring(5, 9) + "000");
        Double maxE = minE + 1000 - 0.25;
        Double maxN = minN + 1000 - 0.25;            
        String bounds = "(["+minE.toString()+","+maxE.toString()+"],["+minN.toString()+","+maxN.toString()+"])"; 
        err.println("-- laz2dsm: " + lazFile);
        try {
            String cmd = "pdal pipeline laz2dsm.json --readers.las.filename="+lazFile+" --writers.gdal.filename="+dsmOrigFile+" --writers.gdal.bounds="+bounds;
            err.println(cmd);
            ProcessBuilder pb = new ProcessBuilder(cmd.split(" "));
            Process p = pb.start();
            {
                BufferedReader is = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line = null;
                while ((line = is.readLine()) != null)
                    err.println(line);
                p.waitFor();
            }
            
            if (p.exitValue() != 0) {
                err.println("Error while processing: " + lazFile);
            }
        } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                err.println(e.getMessage());
        }

        String dsmFillNoDataFile = Paths.get(DSM_DIRECTORY, "filled_no_data_"+lasFileName.replace(".las", ".tif")).toFile().getAbsolutePath();
        err.println("-- fill no data");
        try {
            String cmd = "gdal_fillnodata.py -md 100 -si 2 "+dsmOrigFile+" "+dsmFillNoDataFile;
            err.println(cmd);
            ProcessBuilder pb = new ProcessBuilder(cmd.split(" "));
            Process p = pb.start();
            {
                BufferedReader is = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line = null;
                while ((line = is.readLine()) != null)
                    err.println(line);
                p.waitFor();
            }
            
            if (p.exitValue() != 0) {
                err.println("Error while processing: " + dsmOrigFile);
            }
        } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                err.println(e.getMessage());
        }

        String dsmShadedReliefFile = Paths.get(DSM_SHADED_RELIEF_DIRECTORY, lasFileName.replace(".las", ".tif")).toFile().getAbsolutePath();
        err.println("-- hillshade dsm");
        try {
            String cmd = "gdaldem hillshade "+dsmFillNoDataFile+" "+dsmShadedReliefFile+" -compute_edges -alt 55 -multidirectional";
            err.println(cmd);
            ProcessBuilder pb = new ProcessBuilder(cmd.split(" "));
            Process p = pb.start();
            {
                BufferedReader is = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line = null;
                while ((line = is.readLine()) != null)
                    err.println(line);
                p.waitFor();
            }
            
            if (p.exitValue() != 0) {
                err.println("Error while processing: " + dsmFillNoDataFile);
            }
        } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                err.println(e.getMessage());
        }

        String dtmOrigFile = Paths.get(DTM_DIRECTORY, "orig_"+lasFileName.replace(".las", ".tif")).toFile().getAbsolutePath();
        err.println("-- laz2dtm: " + lazFile);
        try {
            String cmd = "pdal pipeline laz2dtm.json --readers.las.filename="+lazFile+" --writers.gdal.filename="+dtmOrigFile+" --writers.gdal.bounds="+bounds;
            err.println(cmd);
            ProcessBuilder pb = new ProcessBuilder(cmd.split(" "));
            Process p = pb.start();
            {
                BufferedReader is = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line = null;
                while ((line = is.readLine()) != null)
                    err.println(line);
                p.waitFor();
            }
            
            if (p.exitValue() != 0) {
                err.println("Error while processing: " + lazFile);
            }
        } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                err.println(e.getMessage());
        }

        String dtmFillNoDataFile = Paths.get(DTM_DIRECTORY, "filled_no_data_"+lasFileName.replace(".las", ".tif")).toFile().getAbsolutePath();
        err.println("-- fill no data dtm");
        try {
            String cmd = "gdal_fillnodata.py -md 500 -si 2 "+dtmOrigFile+" "+dtmFillNoDataFile;
            err.println(cmd);
            ProcessBuilder pb = new ProcessBuilder(cmd.split(" "));
            Process p = pb.start();
            {
                BufferedReader is = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line = null;
                while ((line = is.readLine()) != null)
                    err.println(line);
                p.waitFor();
            }
            
            if (p.exitValue() != 0) {
                err.println("Error while processing: " + dtmOrigFile);
            }
        } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                err.println(e.getMessage());
        }

        String dtmShadedReliefFile = Paths.get(DTM_SHADED_RELIEF_DIRECTORY, lasFileName.replace(".las", ".tif")).toFile().getAbsolutePath();
        err.println("-- hillshade dtm");
        try {
            String cmd = "gdaldem hillshade "+dtmFillNoDataFile+" "+dtmShadedReliefFile+" -compute_edges -alt 50 -multidirectional";
            err.println(cmd);
            ProcessBuilder pb = new ProcessBuilder(cmd.split(" "));
            Process p = pb.start();
            {
                BufferedReader is = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line = null;
                while ((line = is.readLine()) != null)
                    err.println(line);
                p.waitFor();
            }
            
            if (p.exitValue() != 0) {
                err.println("Error while processing: " + dtmFillNoDataFile);
            }
        } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                err.println(e.getMessage());
        }

        String nDsmBuildingsFile = Paths.get(NDSM_BUILDINGS_DIRECTORY, lasFileName.replace(".las", ".tif")).toFile().getAbsolutePath();
        err.println("-- ndsm buildings");
        try {
            String cmd = "pdal pipeline laz2buildings.json --readers.las.filename="+lazFile+" --writers.gdal.filename="+nDsmBuildingsFile+" --writers.gdal.bounds="+bounds;
            err.println(cmd);
            ProcessBuilder pb = new ProcessBuilder(cmd.split(" "));
            Process p = pb.start();
            {
                BufferedReader is = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line = null;
                while ((line = is.readLine()) != null)
                    err.println(line);
                p.waitFor();
            }
            
            if (p.exitValue() != 0) {
                err.println("Error while processing: " + lazFile);
            }
        } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                err.println(e.getMessage());
        }

        String nDsmVegetationFile = Paths.get(NDSM_VEGETATION_DIRECTORY, lasFileName.replace(".las", ".tif")).toFile().getAbsolutePath();
        err.println("-- ndsm vegetation");
        try {
            String cmd = "pdal pipeline laz2vegetation.json --readers.las.filename="+lazFile+" --writers.gdal.filename="+nDsmVegetationFile+" --writers.gdal.bounds="+bounds;
            err.println(cmd);
            ProcessBuilder pb = new ProcessBuilder(cmd.split(" "));
            Process p = pb.start();
            {
                BufferedReader is = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line = null;
                while ((line = is.readLine()) != null)
                    err.println(line);
                p.waitFor();
            }
            
            if (p.exitValue() != 0) {
                err.println("Error while processing: " + lazFile);
            }
        } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                err.println(e.getMessage());
        }

        Files.delete(Paths.get(zipFile.getAbsolutePath()));
        Files.delete(Paths.get(lasFile));
        Files.delete(Paths.get(lazFile));
        Files.delete(Paths.get(dsmOrigFile));
        Files.delete(Paths.get(dtmOrigFile));
    }
}
