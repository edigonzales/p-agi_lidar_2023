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

public class ProcessData {


    static final String CSV_FILE_NAME = "ch.swisstopo.swisssurface3d-raster-LL3uCeEV.csv";
    static final String TMP_DIRECTORY = "/tmp/";
    static final String TIF_DIRECTORY = "/tmp/tif/";
    static final String RELIEF_DIRECTORY = "/tmp/relief/";
    static final String ZIP_DIRECTORY = System.getProperty("user.home")+"/tmp/zip";
    static final String LAZ_DIRECTORY = System.getProperty("user.home")+"/tmp/laz";
    static final String DSM_DIRECTORY = System.getProperty("user.home")+"/tmp/dsm";
    static final String DSM_SHADED_RELIEF_DIRECTORY = System.getProperty("user.home")+"/tmp/dsm_shaded_relief";
    static final String DTM_DIRECTORY = System.getProperty("user.home")+"/tmp/dtm";
    static final String DTM_SLOPE_DIRECTORY = System.getProperty("user.home")+"/tmp/dtm_slope";
    static final String DTM_SHADED_RELIEF_DIRECTORY = System.getProperty("user.home")+"/tmp/dtm_shaded_relief";
    static final String NDSM_BUILDINGS_DIRECTORY = System.getProperty("user.home")+"/tmp/ndsm_buildings";
    static final String NDSM_VEGETATION_DIRECTORY = System.getProperty("user.home")+"/tmp/ndsm_vegetation";


    public static void main(String... args) throws IOException, URISyntaxException {
        List<String> fileLocations;
        try (Stream<String> lines = Files.lines(Paths.get(CSV_FILE_NAME))) {
            fileLocations = lines.collect(Collectors.toList());
        }

        int maxThreads = 1; 
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
        // String tifFileName = fileName.substring(20,29).replace("-", "_")+".tif";
        err.println("-- Download: " + fileLocation);
        URI uri = new URI(fileLocation);
        File tifFile = Paths.get(TIF_DIRECTORY).resolve(fileName).toFile();
        ReadableByteChannel readableByteChannel = Channels.newChannel(uri.toURL().openStream());
        try(FileOutputStream fileOutputStream = new FileOutputStream(tifFile);) {
            FileChannel fileChannel = fileOutputStream.getChannel();
            fileChannel.transferFrom(readableByteChannel, 0, Long.MAX_VALUE);    
        }

        err.println(tifFile);

        String reliefFile = Paths.get(RELIEF_DIRECTORY, tifFile.getName()).toAbsolutePath().toString();
        err.println(reliefFile);

        err.println("-- hillshade dom");
        try {
            String cmd = "gdaldem hillshade "+tifFile.getAbsolutePath().toString()+" "+reliefFile+" -compute_edges -multidirectional -alt 55 -co TILED=YES -co COMPRESS=DEFLATE -co PREDICTOR=2";
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
                err.println("Error while processing: " + tifFile.toString());
            }
        } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                err.println(e.getMessage());
        }
        
        // Files.delete(Paths.get(zipFile.getAbsolutePath()));
        // Files.delete(Paths.get(lasFile));
        // // Files.delete(Paths.get(lazFile));
        // Files.delete(Paths.get(dsmOrigFile));
        // Files.delete(Paths.get(dsmFillNoDataUncompressedFile));
        // Files.delete(Paths.get(dtmFillNoDataUncompressedFile));
        // Files.delete(Paths.get(dtmOrigFile));
    }
}
