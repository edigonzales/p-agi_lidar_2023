///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS net.lingala.zip4j:zip4j:2.11.5 io.pdal:pdal-native:2.6.2 io.pdal:pdal_3:2.6.2

import static java.lang.System.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.pdal.LogLevel;
import io.pdal.Pipeline;
import net.lingala.zip4j.ZipFile;

public class preprocess {


    static final String CSV_FILE_NAME = "ch.swisstopo.swisssurface3d-dev.csv";
    static final String TMP_DIRECTORY = "/tmp/";
    static final String ZIP_DIRECTORY = System.getProperty("user.home")+"/tmp/zip";
    static final String OUT_DIRECTORY = System.getProperty("user.home")+"/tmp/laz";

    public static void main(String... args) throws IOException, URISyntaxException {
        List<String> fileLocations;
        try (Stream<String> lines = Files.lines(Paths.get(CSV_FILE_NAME))) {
            fileLocations = lines.collect(Collectors.toList());
        }

        for (String fileLocation : fileLocations) {
            String fileName = fileLocation.substring(fileLocation.lastIndexOf("/")+1);
            String lasFileName = fileName.substring(20,29).replace("-", "_")+".las";

            URI uri = new URI(fileLocation);
            File zipFile = Paths.get(ZIP_DIRECTORY).resolve(fileName).toFile();
            ReadableByteChannel readableByteChannel = Channels.newChannel(uri.toURL().openStream());
            try(FileOutputStream fileOutputStream = new FileOutputStream(zipFile);) {
                FileChannel fileChannel = fileOutputStream.getChannel();
                fileChannel.transferFrom(readableByteChannel, 0, Long.MAX_VALUE);    
            }
            
            new ZipFile(zipFile).extractFile(lasFileName, Paths.get(ZIP_DIRECTORY).toString());
    
            String inputFile = Paths.get(ZIP_DIRECTORY, lasFileName).toFile().getAbsolutePath();
            String outputFile = Paths.get(OUT_DIRECTORY, lasFileName.replace(".las", ".laz")).toFile().getAbsolutePath();
    
            String json = """
{
    "pipeline": [
        {
            "type": "readers.las",
            "filename": "%s"
        },
        {
            "type": "writers.las",
            "filename": "%s",
            "compression": "laszip"
        }
    ]
}
            """.formatted(inputFile, outputFile);

            err.println(json);
    
            Pipeline pipeline = new Pipeline(json, LogLevel.Error());
            pipeline.execute(); 
            pipeline.close();
        }
    }
}
