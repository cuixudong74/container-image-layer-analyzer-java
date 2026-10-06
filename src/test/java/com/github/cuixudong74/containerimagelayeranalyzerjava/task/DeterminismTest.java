package com.github.cuixudong74.containerimagelayeranalyzerjava.task;

import com.github.cuixudong74.containerimagelayeranalyzerjava.config.AnalyzerProperties;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.ImageReport;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.TaskStatus;
import com.github.cuixudong74.containerimagelayeranalyzerjava.report.ReportWriter;
import com.github.cuixudong74.containerimagelayeranalyzerjava.testutil.ImageFixtures;
import com.github.cuixudong74.containerimagelayeranalyzerjava.testutil.TarBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Reports must be byte-identical regardless of concurrency and interleaving. */
class DeterminismTest {

    @TempDir
    Path tmp;

    private static void waitDone(ScanTaskManager m, String id) {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        Supplier<Boolean> done = () -> m.status(id).map(s -> s.status() == TaskStatus.COMPLETED).orElse(false);
        while (System.nanoTime() < deadline) {
            if (done.get()) {
                return;
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail();
            }
        }
        fail("task did not complete");
    }

    @Test
    void sameReportAtAnyConcurrency() throws Exception {
        byte[][] layers = new byte[6][];
        for (int i = 0; i < 6; i++) {
            layers[i] = new TarBuilder()
                    .file("shared.bin", "S".repeat(100 * (i + 1)))
                    .file("unique" + i + ".bin", "U".repeat(50))
                    .file("dir/.wh.old" + i, new byte[0])
                    .build();
        }
        layers[0] = new TarBuilder()
                .file("shared.bin", "S".repeat(100))
                .file("dir/old1", "x".repeat(10))
                .file("dir/old2", "y".repeat(20))
                .build();
        Path img = ImageFixtures.dockerImageDir(tmp.resolve("img"), layers);

        ReportWriter writer = new ReportWriter();
        List<String> jsons = new ArrayList<>();
        for (int threads : new int[]{1, 2, 8}) {
            AnalyzerProperties props = new AnalyzerProperties();
            props.setWorkerThreads(threads);
            try (ScanTaskManager m = new ScanTaskManager(props)) {
                // run several tasks at once to force interleaving
                List<String> ids = new ArrayList<>();
                for (int k = 0; k < 3; k++) {
                    ids.add(m.submitScan(img));
                }
                for (String id : ids) {
                    waitDone(m, id);
                    jsons.add(writer.toJson((ImageReport) m.result(id).orElseThrow()));
                }
            }
        }
        for (int i = 1; i < jsons.size(); i++) {
            assertEquals(jsons.get(0), jsons.get(i), "report differs at index " + i);
        }
    }
}
