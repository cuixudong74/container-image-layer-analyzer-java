package com.github.cuixudong74.containerimagelayeranalyzerjava.analyzer;

import com.github.cuixudong74.containerimagelayeranalyzerjava.config.AnalyzerProperties;
import com.github.cuixudong74.containerimagelayeranalyzerjava.input.DirectoryImageSource;
import com.github.cuixudong74.containerimagelayeranalyzerjava.input.ArchiveImageSource;
import com.github.cuixudong74.containerimagelayeranalyzerjava.input.ImageSource;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.EntryType;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.ImageReport;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.IssueReason;
import com.github.cuixudong74.containerimagelayeranalyzerjava.testutil.ImageFixtures;
import com.github.cuixudong74.containerimagelayeranalyzerjava.testutil.TarBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ImageAnalyzerTest {

    @TempDir
    Path tmp;

    private static ProgressListener noopProgress() {
        return new ProgressListener() {
            @Override public void onLayerStart(int layerIndex, String layerId) { }
            @Override public void onBytesScanned(long bytes) { }
            @Override public void onLayerDone(int layerIndex) { }
            @Override public boolean isCancelled() { return false; }
        };
    }

    private ImageAnalyzer analyzer() {
        return new ImageAnalyzer(new AnalyzerProperties());
    }

    @Test
    void crossLayerOverrideIsReclaimable() throws Exception {
        byte[] l0 = new TarBuilder().file("app.bin", new byte[1000]).build();
        byte[] l1 = new TarBuilder().file("app.bin", new byte[100]).build();
        Path dir = ImageFixtures.dockerImageDir(tmp.resolve("img"), l0, l1);
        try (ImageSource src = new DirectoryImageSource(dir, 4096)) {
            ImageReport r = analyzer().analyze(src, noopProgress());
            assertEquals(1100, r.totalBytes());
            assertEquals(100, r.finalFilesystemBytes());
            assertEquals(1000, r.reclaimableBytes());
            assertEquals(1000L, r.layers().get(0).redundantBytes());
            assertEquals(0L, r.layers().get(1).redundantBytes());
            assertEquals("app.bin", r.topReclaimablePaths().get(0).path());
            assertEquals(0, r.topReclaimableLayers().get(0).layerIndex());
        }
    }

    @Test
    void whiteoutDeletesLowerFile() throws Exception {
        byte[] l0 = new TarBuilder().file("etc/conf", "x".repeat(500)).build();
        byte[] l1 = new TarBuilder().file("etc/.wh.conf", new byte[0]).build();
        Path dir = ImageFixtures.dockerImageDir(tmp.resolve("img"), l0, l1);
        try (ImageSource src = new DirectoryImageSource(dir, 4096)) {
            ImageReport r = analyzer().analyze(src, noopProgress());
            assertEquals(0, r.finalFilesystemBytes());
            assertEquals(500, r.reclaimableBytes());
            assertEquals(1L, r.layers().get(1).countByType().get(EntryType.WHITEOUT));
        }
    }

    @Test
    void opaqueDirHidesLowerChildren() throws Exception {
        byte[] l0 = new TarBuilder()
                .dir("d")
                .file("d/old.txt", "y".repeat(300))
                .build();
        byte[] l1 = new TarBuilder()
                .dir("d")
                .file("d/.wh..wh..opq", new byte[0])
                .file("d/new.txt", "z".repeat(50))
                .build();
        Path dir = ImageFixtures.dockerImageDir(tmp.resolve("img"), l0, l1);
        try (ImageSource src = new DirectoryImageSource(dir, 4096)) {
            ImageReport r = analyzer().analyze(src, noopProgress());
            assertEquals(50, r.finalFilesystemBytes());
            assertEquals(300, r.reclaimableBytes());
            assertEquals(1L, r.layers().get(1).countByType().get(EntryType.OPAQUE_MARKER));
        }
    }

    @Test
    void hardlinksAndSymlinksClassified() throws Exception {
        byte[] l0 = new TarBuilder()
                .file("a.txt", "hello")
                .hardlink("b.txt", "a.txt")
                .symlink("c.txt", "a.txt")
                .dir("sub")
                .build();
        Path dir = ImageFixtures.dockerImageDir(tmp.resolve("img"), l0);
        try (ImageSource src = new DirectoryImageSource(dir, 4096)) {
            ImageReport r = analyzer().analyze(src, noopProgress());
            assertEquals(5, r.finalFilesystemBytes());
            assertEquals(1L, r.countByType().get(EntryType.HARDLINK));
            assertEquals(1L, r.countByType().get(EntryType.SYMLINK));
            assertEquals(1L, r.countByType().get(EntryType.DIRECTORY));
            assertEquals(1L, r.countByType().get(EntryType.FILE));
        }
    }

    @Test
    void duplicateContentDetectedAcrossLayers() throws Exception {
        byte[] l0 = new TarBuilder().file("x.bin", "same-content".getBytes()).build();
        byte[] l1 = new TarBuilder().file("y.bin", "same-content".getBytes()).build();
        Path dir = ImageFixtures.dockerImageDir(tmp.resolve("img"), l0, l1);
        try (ImageSource src = new DirectoryImageSource(dir, 4096)) {
            ImageReport r = analyzer().analyze(src, noopProgress());
            assertEquals("same-content".length(), r.duplicateContentBytes());
            assertEquals(0, r.reclaimableBytes());
        }
    }

    @Test
    void corruptedLayerFailsWithReason() throws Exception {
        byte[] good = new TarBuilder().file("a.txt", "hi").build();
        byte[] bad = new TarBuilder().file("b.txt", "hello").build();
        bad[10] = 'Z'; // checksum mismatch in layer 1
        Path dir = ImageFixtures.dockerImageDir(tmp.resolve("img"), good, bad);
        try (ImageSource src = new DirectoryImageSource(dir, 4096)) {
            LayerScanException e = assertThrows(LayerScanException.class,
                    () -> analyzer().analyze(src, noopProgress()));
            assertEquals(1, e.getIssue().layerIndex());
            assertEquals(IssueReason.CHECKSUM_MISMATCH, e.getIssue().reason());
        }
    }

    @Test
    void pathTraversalFailsWithReason() throws Exception {
        byte[] bad = new TarBuilder().file("../evil", "x").build();
        Path dir = ImageFixtures.dockerImageDir(tmp.resolve("img"), bad);
        try (ImageSource src = new DirectoryImageSource(dir, 4096)) {
            LayerScanException e = assertThrows(LayerScanException.class,
                    () -> analyzer().analyze(src, noopProgress()));
            assertEquals(IssueReason.PATH_TRAVERSAL, e.getIssue().reason());
        }
    }

    @Test
    void archiveInputMatchesDirectoryInput() throws Exception {
        byte[] l0 = new TarBuilder().file("a.txt", "hello").build();
        byte[] l1 = new TarBuilder().file("a.txt", "hi").file("b.txt", "world").build();
        Path dir = ImageFixtures.dockerImageDir(tmp.resolve("img"), l0, l1);
        Path archive = tmp.resolve("img.tar");
        Files.write(archive, ImageFixtures.dockerArchive(l0, l1));
        ImageReport fromDir;
        ImageReport fromArchive;
        try (ImageSource src = new DirectoryImageSource(dir, 4096)) {
            fromDir = analyzer().analyze(src, noopProgress());
        }
        try (ImageSource src = new ArchiveImageSource(archive, 4096)) {
            fromArchive = analyzer().analyze(src, noopProgress());
        }
        assertEquals(fromDir, fromArchive);
    }

    @Test
    void progressCallbacksFireAndCountsBytes() throws Exception {
        byte[] l0 = new TarBuilder().file("a.txt", "hello").build();
        Path dir = ImageFixtures.dockerImageDir(tmp.resolve("img"), l0);
        AtomicLong bytes = new AtomicLong();
        AtomicLong layerStarts = new AtomicLong();
        try (ImageSource src = new DirectoryImageSource(dir, 4096)) {
            analyzer().analyze(src, new ProgressListener() {
                @Override public void onLayerStart(int i, String id) { layerStarts.incrementAndGet(); }
                @Override public void onBytesScanned(long b) { bytes.addAndGet(b); }
                @Override public void onLayerDone(int i) { }
                @Override public boolean isCancelled() { return false; }
            });
        }
        assertEquals(1, layerStarts.get());
        assertEquals(5, bytes.get());
    }

    @Test
    void cancellationAbortsScan() throws Exception {
        byte[] l0 = new TarBuilder().file("a.txt", "hello").build();
        Path dir = ImageFixtures.dockerImageDir(tmp.resolve("img"), l0);
        try (ImageSource src = new DirectoryImageSource(dir, 4096)) {
            assertThrows(CancellationException.class, () -> analyzer().analyze(src, new ProgressListener() {
                @Override public void onLayerStart(int i, String id) { }
                @Override public void onBytesScanned(long b) { }
                @Override public void onLayerDone(int i) { }
                @Override public boolean isCancelled() { return true; }
            }));
        }
    }

    @Test
    void reportIsDeterministicAcrossRuns() throws Exception {
        byte[] l0 = new TarBuilder()
                .file("z.txt", "1".repeat(100))
                .file("a.txt", "2".repeat(200))
                .dir("m")
                .file("m/x.txt", "3".repeat(50))
                .build();
        byte[] l1 = new TarBuilder().file("a.txt", "4".repeat(20)).build();
        Path dir = ImageFixtures.dockerImageDir(tmp.resolve("img"), l0, l1);
        ImageReport r1;
        ImageReport r2;
        try (ImageSource src = new DirectoryImageSource(dir, 4096)) {
            r1 = analyzer().analyze(src, noopProgress());
        }
        try (ImageSource src = new DirectoryImageSource(dir, 4096)) {
            r2 = analyzer().analyze(src, noopProgress());
        }
        assertEquals(r1, r2);
    }
}
