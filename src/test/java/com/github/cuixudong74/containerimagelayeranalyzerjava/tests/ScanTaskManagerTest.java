package com.github.cuixudong74.containerimagelayeranalyzerjava.tests;

import com.github.cuixudong74.containerimagelayeranalyzerjava.config.AnalyzerProperties;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.ImageReport;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.TaskStatus;
import com.github.cuixudong74.containerimagelayeranalyzerjava.report.ReportWriter;
import com.github.cuixudong74.containerimagelayeranalyzerjava.task.ScanTask;
import com.github.cuixudong74.containerimagelayeranalyzerjava.task.ScanTaskManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 并发、公平调度、取消与超时测试。 */
class ScanTaskManagerTest {
    @TempDir
    Path tmp;

    private AnalyzerProperties props(int workers, long timeoutMillis) {
        AnalyzerProperties p = new AnalyzerProperties();
        p.setWorkerThreads(workers);
        p.setTaskTimeoutMillis(timeoutMillis);
        return p;
    }

    private static Path manyLayerImage(Path dir, int layers) throws Exception {
        List<TestImages.Layer> list = new ArrayList<>();
        for (int i = 0; i < layers; i++) {
            list.add(TestImages.layer().file("/f" + i, "content-" + i));
        }
        return TestImages.writeImage(dir, list);
    }

    private static ScanTask awaitTerminal(ScanTaskManager m, String id, long millis)
            throws Exception {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            ScanTask t = m.getTask(id);
            if (t != null && t.isTerminal()) {
                return t;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("task did not reach terminal state: " + id);
    }

    @Test
    void concurrentScansProduceIdenticalReports() throws Exception {
        Path image = manyLayerImage(tmp.resolve("img"), 8);
        try (ScanTaskManager m = new ScanTaskManager(props(2, 60_000))) {
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                ids.add(m.submitScan(image));
            }
            byte[] reference = null;
            for (String id : ids) {
                ScanTask t = awaitTerminal(m, id, 30_000);
                assertEquals(TaskStatus.SUCCEEDED, t.getStatus());
                byte[] report = ReportWriter.toJsonBytes(m.getReport(id));
                if (reference == null) {
                    reference = report;
                } else {
                    assertTrue(java.util.Arrays.equals(reference, report),
                            "任意并发度下报告必须逐字节一致");
                }
            }
        }
    }

    @Test
    void smallTaskIsNotStarvedByLargeTask() throws Exception {
        Path big = manyLayerImage(tmp.resolve("big"), 200);
        Path small = manyLayerImage(tmp.resolve("small"), 1);
        try (ScanTaskManager m = new ScanTaskManager(props(1, 120_000))) {
            String bigId = m.submitScan(big);
            String smallId = m.submitScan(small);
            ScanTask smallTask = awaitTerminal(m, smallId, 60_000);
            ScanTask bigTask = awaitTerminal(m, bigId, 60_000);
            assertEquals(TaskStatus.SUCCEEDED, smallTask.getStatus());
            assertEquals(TaskStatus.SUCCEEDED, bigTask.getStatus());
            assertTrue(smallTask.getFinishedAtMillis() <= bigTask.getFinishedAtMillis(),
                    "轮转调度下小任务不应被大任务饿死");
        }
    }

    @Test
    void cancelledTaskStopsAndReleasesResources() throws Exception {
        Path image = manyLayerImage(tmp.resolve("img"), 500);
        try (ScanTaskManager m = new ScanTaskManager(props(1, 120_000))) {
            String id = m.submitScan(image);
            // 等任务跑起来再取消
            long deadline = System.currentTimeMillis() + 10_000;
            while (m.getTask(id).getStatus() != TaskStatus.RUNNING
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(5);
            }
            m.cancel(id);
            ScanTask t = awaitTerminal(m, id, 30_000);
            assertEquals(TaskStatus.CANCELLED, t.getStatus());
            // 后续任务不受影响
            String id2 = m.submitScan(image);
            ScanTask t2 = awaitTerminal(m, id2, 60_000);
            assertEquals(TaskStatus.SUCCEEDED, t2.getStatus());
        }
    }

    @Test
    void timeoutTaskIsDistinguished() throws Exception {
        Path image = manyLayerImage(tmp.resolve("img"), 500);
        try (ScanTaskManager m = new ScanTaskManager(props(1, 1))) {
            String id = m.submitScan(image);
            ScanTask t = awaitTerminal(m, id, 30_000);
            assertEquals(TaskStatus.TIMEOUT, t.getStatus());
        }
    }

    @Test
    void cancelledOptimizeLeavesNoResidue() throws Exception {
        Path image = manyLayerImage(tmp.resolve("img"), 500);
        Path out = tmp.resolve("optimized");
        try (ScanTaskManager m = new ScanTaskManager(props(1, 120_000))) {
            String id = m.submitOptimize(image, out);
            long deadline = System.currentTimeMillis() + 10_000;
            while (m.getTask(id).getStatus() != TaskStatus.RUNNING
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(5);
            }
            m.cancel(id);
            ScanTask t = awaitTerminal(m, id, 30_000);
            assertEquals(TaskStatus.CANCELLED, t.getStatus());
            assertFalse(Files.exists(out), "取消不得留下产物");
            try (Stream<Path> s = Files.list(tmp)) {
                assertTrue(s.noneMatch(p -> p.getFileName().toString().startsWith(".optimize-")),
                        "取消不得残留临时目录");
            }
        }
    }

    @Test
    void failedTaskReportsError() throws Exception {
        Path missing = tmp.resolve("does-not-exist");
        try (ScanTaskManager m = new ScanTaskManager(props(1, 60_000))) {
            String id = m.submitScan(missing);
            ScanTask t = awaitTerminal(m, id, 30_000);
            assertEquals(TaskStatus.FAILED, t.getStatus());
            assertNotNull(t.getErrorMessage());
        }
    }
}
