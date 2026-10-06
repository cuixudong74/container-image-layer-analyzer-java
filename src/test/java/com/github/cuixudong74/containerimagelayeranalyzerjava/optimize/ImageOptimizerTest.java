package com.github.cuixudong74.containerimagelayeranalyzerjava.optimize;

import com.github.cuixudong74.containerimagelayeranalyzerjava.analyzer.ProgressListener;
import com.github.cuixudong74.containerimagelayeranalyzerjava.analyzer.ScanSession;
import com.github.cuixudong74.containerimagelayeranalyzerjava.config.AnalyzerProperties;
import com.github.cuixudong74.containerimagelayeranalyzerjava.input.DirectoryImageSource;
import com.github.cuixudong74.containerimagelayeranalyzerjava.input.ImageSource;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.EntryType;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.ImageReport;
import com.github.cuixudong74.containerimagelayeranalyzerjava.overlay.FsNode;
import com.github.cuixudong74.containerimagelayeranalyzerjava.testutil.ImageFixtures;
import com.github.cuixudong74.containerimagelayeranalyzerjava.testutil.TarBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class ImageOptimizerTest {

    @TempDir
    Path tmp;

    private static ProgressListener noop() {
        return new ProgressListener() {
            @Override public void onLayerStart(int i, String id) { }
            @Override public void onBytesScanned(long b) { }
            @Override public void onLayerDone(int i) { }
            @Override public boolean isCancelled() { return false; }
        };
    }

    /** Final filesystem of an image, ignoring which layer each node came from. */
    private static Map<String, FsNode> finalFsOf(Path imageDir) throws Exception {
        try (ImageSource src = new DirectoryImageSource(imageDir, 4096);
             ScanSession session = new ScanSession(src, new AnalyzerProperties())) {
            while (session.hasNextLayer()) {
                session.scanNextLayer(noop());
            }
            Map<String, FsNode> raw = session.overlay().finalFilesystem();
            Map<String, FsNode> normalized = new TreeMap<>();
            raw.forEach((p, n) -> normalized.put(p,
                    new FsNode(p, n.type(), n.size(), n.linkTarget(), n.sha256(), 0)));
            return normalized;
        }
    }

    private byte[][] sampleLayers() {
        byte[] l0 = new TarBuilder()
                .file("app/data.bin", "A".repeat(2000))
                .file("app/deleted.txt", "B".repeat(500))
                .file("etc/config", "C".repeat(100))
                .dir("emptydir")
                .build();
        byte[] l1 = new TarBuilder()
                .file("app/.wh.deleted.txt", new byte[0])
                .file("app/data.bin", "D".repeat(300))
                .file("app/copy-of-config", "C".repeat(100)) // duplicate content of etc/config
                .symlink("app/link", "etc/config")
                .hardlink("app/hard", "etc/config")
                .build();
        return new byte[][]{l0, l1};
    }

    @Test
    void optimizedImageHasEquivalentFinalFilesystem() throws Exception {
        byte[][] layers = sampleLayers();
        Path src = ImageFixtures.dockerImageDir(tmp.resolve("src"), layers);
        Path out = tmp.resolve("optimized");

        Map<String, FsNode> before = finalFsOf(src);
        ImageReport report;
        try (ImageSource s = new DirectoryImageSource(src, 4096)) {
            report = new ImageOptimizer(new AnalyzerProperties()).optimize(s, out);
        }
        Map<String, FsNode> after = finalFsOf(out);

        // Content dedup may turn a FILE into a HARDLINK to an identical file;
        // resolve hardlinks before comparing content.
        assertEquals(before.keySet(), after.keySet());
        for (String p : before.keySet()) {
            FsNode b = before.get(p);
            FsNode a = after.get(p);
            if (a.type() == EntryType.HARDLINK && b.type() == EntryType.FILE) {
                FsNode target = after.get(a.linkTarget());
                assertNotNull(target, "hardlink target missing: " + a.linkTarget());
                assertEquals(b.sha256(), target.sha256(), p);
                assertEquals(b.size(), target.size(), p);
                continue;
            }
            assertEquals(b.type(), a.type(), p);
            assertEquals(b.size(), a.size(), p);
            if (b.type() == EntryType.FILE) {
                assertEquals(b.sha256(), a.sha256(), p);
            }
            if (b.type() == EntryType.SYMLINK) {
                assertEquals(b.linkTarget(), a.linkTarget(), p);
            }
        }

        // redundancy accounted
        assertEquals(2000 + 500, report.reclaimableBytes());
        assertEquals(100, report.duplicateContentBytes());

        // no staging leftovers
        assertFalse(Files.exists(tmp.resolve("optimized.tmp")));
        assertTrue(Files.exists(out.resolve("manifest.json")));
    }

    @Test
    void optimizedImageIsSmaller() throws Exception {
        byte[][] layers = sampleLayers();
        Path src = ImageFixtures.dockerImageDir(tmp.resolve("src"), layers);
        Path out = tmp.resolve("optimized");
        try (ImageSource s = new DirectoryImageSource(src, 4096)) {
            new ImageOptimizer(new AnalyzerProperties()).optimize(s, out);
        }
        long optimizedLayerSize = Files.size(out.resolve("optimized0/layer.tar"));
        long originalPayload = 2000 + 500 + 100 + 300 + 100;
        // squashed layer holds: 300 (data.bin) + 100 (config) + 100 (copy, as
        // hardlink -> 0) + metadata; well below the original payload sum.
        assertTrue(optimizedLayerSize < originalPayload + 4096,
                "optimized layer too big: " + optimizedLayerSize);
    }

    @Test
    void emptyFinalFilesystemProducesValidEmptyLayer() throws Exception {
        byte[] l0 = new TarBuilder().file("a.txt", "x").build();
        byte[] l1 = new TarBuilder().file(".wh.a.txt", new byte[0]).build();
        Path src = ImageFixtures.dockerImageDir(tmp.resolve("src"), l0, l1);
        Path out = tmp.resolve("optimized");
        try (ImageSource s = new DirectoryImageSource(src, 4096)) {
            new ImageOptimizer(new AnalyzerProperties()).optimize(s, out);
        }
        Map<String, FsNode> after = finalFsOf(out);
        assertTrue(after.isEmpty());
    }

    @Test
    void optimizationIsDeterministic() throws Exception {
        byte[][] layers = sampleLayers();
        Path src = ImageFixtures.dockerImageDir(tmp.resolve("src"), layers);
        ImageReport r1;
        ImageReport r2;
        try (ImageSource s = new DirectoryImageSource(src, 4096)) {
            r1 = new ImageOptimizer(new AnalyzerProperties()).optimize(s, tmp.resolve("o1"));
        }
        try (ImageSource s = new DirectoryImageSource(src, 4096)) {
            r2 = new ImageOptimizer(new AnalyzerProperties()).optimize(s, tmp.resolve("o2"));
        }
        assertEquals(r1, r2);
        byte[] t1 = Files.readAllBytes(tmp.resolve("o1/optimized0/layer.tar"));
        byte[] t2 = Files.readAllBytes(tmp.resolve("o2/optimized0/layer.tar"));
        assertArrayEquals(t1, t2);
    }
}
