package com.github.cuixudong74.containerimagelayeranalyzerjava.testutil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Builds synthetic docker-save images (archive bytes or exploded dirs) for tests. */
public final class ImageFixtures {

    private ImageFixtures() {
    }

    /** Builds a docker-save archive containing the given layer tars. */
    public static byte[] dockerArchive(byte[]... layerTars) {
        StringBuilder manifest = new StringBuilder("[{\"Config\":\"testconfig.json\",\"Layers\":[");
        for (int i = 0; i < layerTars.length; i++) {
            if (i > 0) {
                manifest.append(',');
            }
            manifest.append('"').append("layer" + i + "/layer.tar").append('"');
        }
        manifest.append("]}]");
        TarBuilder all = new TarBuilder()
                .file("manifest.json", manifest.toString().getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < layerTars.length; i++) {
            all.raw("layer" + i + "/layer.tar", '0', layerTars[i], "");
        }
        return all.build();
    }

    /** Writes an exploded docker-save directory. */
    public static Path dockerImageDir(Path dir, byte[]... layerTars) throws IOException {
        Files.createDirectories(dir);
        StringBuilder manifest = new StringBuilder("[{\"Config\":\"testconfig.json\",\"Layers\":[");
        for (int i = 0; i < layerTars.length; i++) {
            Path sub = dir.resolve("layer" + i);
            Files.createDirectories(sub);
            Files.write(sub.resolve("layer.tar"), layerTars[i]);
            if (i > 0) {
                manifest.append(',');
            }
            manifest.append('"').append("layer" + i + "/layer.tar").append('"');
        }
        manifest.append("]}]");
        Files.write(dir.resolve("manifest.json"), manifest.toString().getBytes(StandardCharsets.UTF_8));
        return dir;
    }
}
