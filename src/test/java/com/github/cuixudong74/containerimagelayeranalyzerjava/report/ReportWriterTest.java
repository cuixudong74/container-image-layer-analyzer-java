package com.github.cuixudong74.containerimagelayeranalyzerjava.report;

import com.github.cuixudong74.containerimagelayeranalyzerjava.analyzer.ImageAnalyzer;
import com.github.cuixudong74.containerimagelayeranalyzerjava.analyzer.ProgressListener;
import com.github.cuixudong74.containerimagelayeranalyzerjava.config.AnalyzerProperties;
import com.github.cuixudong74.containerimagelayeranalyzerjava.input.DirectoryImageSource;
import com.github.cuixudong74.containerimagelayeranalyzerjava.input.ImageSource;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.ImageReport;
import com.github.cuixudong74.containerimagelayeranalyzerjava.testutil.ImageFixtures;
import com.github.cuixudong74.containerimagelayeranalyzerjava.testutil.TarBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReportWriterTest {

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

    private ImageReport sampleReport() throws Exception {
        byte[] l0 = new TarBuilder()
                .file("b.txt", "2".repeat(200))
                .file("a.txt", "1".repeat(100))
                .build();
        byte[] l1 = new TarBuilder().file("a.txt", "3".repeat(50)).build();
        Path dir = ImageFixtures.dockerImageDir(tmp.resolve("img"), l0, l1);
        try (ImageSource src = new DirectoryImageSource(dir, 4096)) {
            return new ImageAnalyzer(new AnalyzerProperties()).analyze(src, noop());
        }
    }

    @Test
    void jsonIsByteIdenticalAcrossSerializations() throws Exception {
        ImageReport r = sampleReport();
        ReportWriter w = new ReportWriter();
        String j1 = w.toJson(r);
        String j2 = w.toJson(r);
        assertEquals(j1, j2);
        assertTrue(j1.contains("\"reclaimableBytes\":100"), j1);
    }

    @Test
    void atomicWriteLeavesNoTempFiles() throws Exception {
        ImageReport r = sampleReport();
        ReportWriter w = new ReportWriter();
        Path target = tmp.resolve("out/report.json");
        w.writeAtomically(r, target);
        assertTrue(Files.exists(target));
        try (var s = Files.list(tmp.resolve("out"))) {
            List<Path> files = s.toList();
            assertEquals(1, files.size());
            assertEquals(target, files.get(0));
        }
        assertEquals(w.toJson(r), Files.readString(target));
    }
}
