package com.github.cuixudong74.containerimagelayeranalyzerjava.input;

import com.github.cuixudong74.containerimagelayeranalyzerjava.testutil.ImageFixtures;
import com.github.cuixudong74.containerimagelayeranalyzerjava.testutil.TarBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ImageSourceTest {

    @TempDir
    Path tmp;

    @Test
    void gzippedArchiveIsTransparentlyDecompressed() throws Exception {
        byte[] l0 = new TarBuilder().file("a.txt", "hello").build();
        byte[] archive = ImageFixtures.dockerArchive(l0);
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(gz)) {
            g.write(archive);
        }
        Path p = tmp.resolve("img.tar.gz");
        Files.write(p, gz.toByteArray());
        try (ImageSource src = ImageSourceFactory.open(p, 4096)) {
            assertEquals(1, src.layers().size());
            assertEquals("testconfig", src.imageId());
            byte[] layerBytes = src.layers().get(0).openStream().readAllBytes();
            assertArrayEquals(l0, layerBytes);
        }
    }

    @Test
    void ociLayoutDirectory() throws Exception {
        byte[] l0 = new TarBuilder().file("x.txt", "data").build();
        String layerDigest = "sha256:" + "ab".repeat(32);
        String manifestDigest = "sha256:" + "cd".repeat(32);
        Path blobs = tmp.resolve("oci/blobs/sha256");
        Files.createDirectories(blobs);
        Files.write(blobs.resolve("ab".repeat(32)), l0);
        String manifest = "{\"schemaVersion\":2,\"layers\":["
                + "{\"mediaType\":\"application/vnd.oci.image.layer.v1.tar\","
                + "\"digest\":\"" + layerDigest + "\",\"size\":" + l0.length + "}]}";
        Files.write(blobs.resolve("cd".repeat(32)), manifest.getBytes());
        String index = "{\"schemaVersion\":2,\"manifests\":["
                + "{\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\","
                + "\"digest\":\"" + manifestDigest + "\",\"size\":" + manifest.length() + "}]}";
        Files.write(tmp.resolve("oci/index.json"), index.getBytes());

        try (ImageSource src = ImageSourceFactory.open(tmp.resolve("oci"), 4096)) {
            assertEquals(1, src.layers().size());
            assertEquals(manifestDigest, src.imageId());
            assertArrayEquals(l0, src.layers().get(0).openStream().readAllBytes());
        }
    }

    @Test
    void ociLayoutArchive() throws Exception {
        byte[] l0 = new TarBuilder().file("x.txt", "data").build();
        String manifest = "{\"schemaVersion\":2,\"layers\":["
                + "{\"mediaType\":\"application/vnd.oci.image.layer.v1.tar\","
                + "\"digest\":\"sha256:" + "ab".repeat(32) + "\",\"size\":" + l0.length + "}]}";
        String index = "{\"schemaVersion\":2,\"manifests\":[{\"digest\":\"sha256:" + "cd".repeat(32)
                + "\",\"size\":" + manifest.length() + "}]}";
        byte[] archive = new TarBuilder()
                .file("index.json", index)
                .file("blobs/sha256/" + "cd".repeat(32), manifest.getBytes())
                .file("blobs/sha256/" + "ab".repeat(32), l0)
                .build();
        Path p = tmp.resolve("oci.tar");
        Files.write(p, archive);
        try (ImageSource src = ImageSourceFactory.open(p, 4096)) {
            assertEquals(1, src.layers().size());
            assertArrayEquals(l0, src.layers().get(0).openStream().readAllBytes());
        }
    }

    @Test
    void missingLayerInArchiveFails() throws Exception {
        byte[] archive = new TarBuilder()
                .file("manifest.json", "[{\"Config\":\"c.json\",\"Layers\":[\"l0/layer.tar\"]}]")
                .build();
        Path p = tmp.resolve("broken.tar");
        Files.write(p, archive);
        assertThrows(java.io.IOException.class, () -> ImageSourceFactory.open(p, 4096));
    }

    @Test
    void neitherFileNorDirectoryFails() {
        assertThrows(java.io.IOException.class,
                () -> ImageSourceFactory.open(tmp.resolve("does-not-exist"), 4096));
    }
}
