package com.github.cuixudong74.containerimagelayeranalyzerjava.task;

import com.github.cuixudong74.containerimagelayeranalyzerjava.config.AnalyzerProperties;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.ImageReport;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.TaskSnapshot;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.TaskStatus;
import com.github.cuixudong74.containerimagelayeranalyzerjava.testutil.ImageFixtures;
import com.github.cuixudong74.containerimagelayeranalyzerjava.testutil.TarBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class ScanTaskManagerTest {

    @TempDir
    Path tmp;

    private static void waitUntil(Supplier<Boolean> cond, Duration timeout, String what) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (cond.get()) {
                return;
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for " + what);
            }
        }
        fail("timed out waiting for " + what);
    }

    private static boolean terminal(TaskStatus s) {
        return s == TaskStatus.COMPLETED || s == TaskStatus.FAILED
                || s == TaskStatus.CANCELLED || s == TaskStatus.TIMEOUT;
    }

    private Path imageWithLayers(String name, int layers, int payloadPerLayer) throws Exception {
        byte[][] ls = new byte[layers][];
        for (int i = 0; i < layers; i++) {
            ls[i] = new TarBuilder()
                    .file("file" + i + ".bin", new byte[payloadPerLayer])
                    .build();
        }
        return ImageFixtures.dockerImageDir(tmp.resolve(name), ls);
    }

    @Test
    void scanTaskCompletesWithReport() throws Exception {
        Path img = imageWithLayers("img", 3, 1000);
        try (ScanTaskManager m = new ScanTaskManager(new AnalyzerProperties())) {
            String id = m.submitScan(img);
            waitUntil(() -> m.status(id).map(s -> terminal(s.status())).orElse(false),
                    Duration.ofSeconds(30), "task completion");
            TaskSnapshot snap = m.status(id).orElseThrow();
            assertEquals(TaskStatus.COMPLETED, snap.status());
            assertEquals(3, snap.layersCompleted());
            assertEquals(3, snap.layersTotal());
            Object result = m.result(id).orElseThrow();
            assertInstanceOf(ImageReport.class, result);
            assertEquals(3000, ((ImageReport) result).totalBytes());
        }
    }

    @Test
    void corruptImageFailsTaskWithReason() throws Exception {
        byte[] bad = new TarBuilder().file("a.txt", "hello").build();
        bad[10] = 'Z';
        Path img = ImageFixtures.dockerImageDir(tmp.resolve("img"), bad);
        try (ScanTaskManager m = new ScanTaskManager(new AnalyzerProperties())) {
            String id = m.submitScan(img);
            waitUntil(() -> m.status(id).map(s -> terminal(s.status())).orElse(false),
                    Duration.ofSeconds(30), "task failure");
            TaskSnapshot snap = m.status(id).orElseThrow();
            assertEquals(TaskStatus.FAILED, snap.status());
            assertNotNull(snap.errorMessage());
            assertTrue(snap.errorMessage().contains("checksum"), snap.errorMessage());
            assertTrue(m.result(id).isEmpty());
        }
    }

    @Test
    void cancellationStopsTask() throws Exception {
        Path img = imageWithLayers("img", 40, 1 << 20);
        try (ScanTaskManager m = new ScanTaskManager(new AnalyzerProperties())) {
            String id = m.submitScan(img);
            assertTrue(m.cancel(id) || m.status(id).map(s -> terminal(s.status())).orElse(false));
            waitUntil(() -> m.status(id).map(s -> terminal(s.status())).orElse(false),
                    Duration.ofSeconds(30), "cancellation");
            TaskStatus st = m.status(id).orElseThrow().status();
            assertTrue(st == TaskStatus.CANCELLED || st == TaskStatus.COMPLETED,
                    "expected CANCELLED (or COMPLETED if it raced), got " + st);
            assertTrue(m.result(id).isEmpty() || st == TaskStatus.COMPLETED);
        }
    }

    @Test
    void timeoutKillsLongTask() throws Exception {
        Path img = imageWithLayers("img", 30, 1 << 20);
        AnalyzerProperties props = new AnalyzerProperties();
        props.setTaskTimeoutMillis(1);
        try (ScanTaskManager m = new ScanTaskManager(props)) {
            String id = m.submitScan(img);
            waitUntil(() -> m.status(id).map(s -> terminal(s.status())).orElse(false),
                    Duration.ofSeconds(30), "timeout");
            assertEquals(TaskStatus.TIMEOUT, m.status(id).orElseThrow().status());
        }
    }

    @Test
    void singleWorkerInterleavesTasksFairly() throws Exception {
        // Task A: many layers. Task B: one layer. With one worker and
        // layer-granular scheduling, B finishes long before A.
        Path big = imageWithLayers("big", 50, 1 << 20);
        Path small = imageWithLayers("small", 1, 1 << 20);
        AnalyzerProperties props = new AnalyzerProperties();
        props.setWorkerThreads(1);
        try (ScanTaskManager m = new ScanTaskManager(props)) {
            String a = m.submitScan(big);
            String b = m.submitScan(small);
            waitUntil(() -> m.status(b).map(s -> terminal(s.status())).orElse(false),
                    Duration.ofSeconds(60), "small task completion");
            TaskSnapshot snapA = m.status(a).orElseThrow();
            assertTrue(snapA.layersCompleted() < 50,
                    "big task should not be finished when small task completes, was "
                            + snapA.layersCompleted());
            waitUntil(() -> m.status(a).map(s -> terminal(s.status())).orElse(false),
                    Duration.ofSeconds(60), "big task completion");
            assertEquals(TaskStatus.COMPLETED, m.status(a).orElseThrow().status());
            assertEquals(TaskStatus.COMPLETED, m.status(b).orElseThrow().status());
        }
    }

    @Test
    void optimizeTaskPublishesAndCleansUpOnCancel() throws Exception {
        Path img = imageWithLayers("img", 30, 1 << 20);
        Path out = tmp.resolve("out");
        try (ScanTaskManager m = new ScanTaskManager(new AnalyzerProperties())) {
            String id = m.submitOptimize(img, out);
            m.cancel(id);
            waitUntil(() -> m.status(id).map(s -> terminal(s.status())).orElse(false),
                    Duration.ofSeconds(30), "optimize cancellation");
            TaskStatus st = m.status(id).orElseThrow().status();
            assertTrue(st == TaskStatus.CANCELLED || st == TaskStatus.COMPLETED);
            // no staging directory may survive either outcome
            assertFalse(Files.exists(tmp.resolve("out.tmp")), "staging dir left behind");
        }
    }

    @Test
    void optimizeTaskProducesEquivalentImage() throws Exception {
        byte[] l0 = new TarBuilder().file("a.bin", new byte[5000]).file("b.bin", new byte[100]).build();
        byte[] l1 = new TarBuilder().file("a.bin", new byte[50]).build();
        Path img = ImageFixtures.dockerImageDir(tmp.resolve("img"), l0, l1);
        Path out = tmp.resolve("out");
        try (ScanTaskManager m = new ScanTaskManager(new AnalyzerProperties())) {
            String id = m.submitOptimize(img, out);
            waitUntil(() -> m.status(id).map(s -> terminal(s.status())).orElse(false),
                    Duration.ofSeconds(30), "optimize completion");
            assertEquals(TaskStatus.COMPLETED, m.status(id).orElseThrow().status());
            ImageReport r = (ImageReport) m.result(id).orElseThrow();
            assertEquals(5000, r.reclaimableBytes());
            assertTrue(Files.exists(out.resolve("manifest.json")));
            assertTrue(Files.exists(out.resolve("optimized0/layer.tar")));
        }
    }

    @Test
    void unknownTaskQueries() {
        try (ScanTaskManager m = new ScanTaskManager(new AnalyzerProperties())) {
            assertTrue(m.status("nope").isEmpty());
            assertFalse(m.cancel("nope"));
            assertTrue(m.result("nope").isEmpty());
        }
    }
}
