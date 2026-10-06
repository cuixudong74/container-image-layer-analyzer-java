package com.github.cuixudong74.containerimagelayeranalyzerjava.task;

import com.github.cuixudong74.containerimagelayeranalyzerjava.analyze.LayerAnalyzer;
import com.github.cuixudong74.containerimagelayeranalyzerjava.analyze.ProgressTracker;
import com.github.cuixudong74.containerimagelayeranalyzerjava.input.ImageSource;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.ImageReport;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.TaskStatus;
import com.github.cuixudong74.containerimagelayeranalyzerjava.optimize.ImageOptimizer;
import com.github.cuixudong74.containerimagelayeranalyzerjava.optimize.OptimizeResult;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一个扫描/优化任务。状态字段全部线程安全，可随时查询。
 * 执行以层为粒度分片推进，由 {@link ScanTaskManager} 调度。
 */
public class ScanTask {
    public enum Kind { SCAN, OPTIMIZE }

    private final String id;
    private final Kind kind;
    private final Path inputPath;
    private final Path outputPath;
    private final long timeoutMillis;
    private final long createdAtMillis = System.currentTimeMillis();

    private volatile TaskStatus status = TaskStatus.PENDING;
    private volatile String errorMessage;
    private volatile ProgressTracker progress = new ProgressTracker(-1);
    private volatile ImageReport report;
    private volatile OptimizeResult optimizeResult;
    private volatile long startedAtMillis = -1;
    private volatile long finishedAtMillis = -1;

    // ---- 执行内部状态（仅调度线程访问） ----
    final AtomicBoolean cancelRequested = new AtomicBoolean();
    ImageSource source;
    LayerAnalyzer.AnalysisSession analysisSession;
    ImageOptimizer.OptimizeSession optimizeSession;

    ScanTask(String id, Kind kind, Path inputPath, Path outputPath, long timeoutMillis) {
        this.id = id;
        this.kind = kind;
        this.inputPath = inputPath;
        this.outputPath = outputPath;
        this.timeoutMillis = timeoutMillis;
    }

    public String getId() {
        return id;
    }

    public Kind getKind() {
        return kind;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public Path getInputPath() {
        return inputPath;
    }

    public Path getOutputPath() {
        return outputPath;
    }

    public ProgressTracker getProgress() {
        return progress;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public long getStartedAtMillis() {
        return startedAtMillis;
    }

    public long getFinishedAtMillis() {
        return finishedAtMillis;
    }

    ImageReport getReportInternal() {
        return report;
    }

    OptimizeResult getOptimizeResultInternal() {
        return optimizeResult;
    }

    // ---- 包内状态迁移 ----

    void markRunning(ProgressTracker tracker) {
        this.progress = tracker;
        this.startedAtMillis = System.currentTimeMillis();
        this.status = TaskStatus.RUNNING;
    }

    void markSucceeded(ImageReport report) {
        this.report = report;
        this.finishedAtMillis = System.currentTimeMillis();
        this.status = TaskStatus.SUCCEEDED;
    }

    void markSucceeded(OptimizeResult result) {
        this.optimizeResult = result;
        this.finishedAtMillis = System.currentTimeMillis();
        this.status = TaskStatus.SUCCEEDED;
    }

    void markFailed(String message) {
        this.errorMessage = message;
        this.finishedAtMillis = System.currentTimeMillis();
        this.status = TaskStatus.FAILED;
    }

    void markCancelled() {
        this.finishedAtMillis = System.currentTimeMillis();
        this.status = TaskStatus.CANCELLED;
    }

    void markTimeout() {
        this.finishedAtMillis = System.currentTimeMillis();
        this.status = TaskStatus.TIMEOUT;
    }

    boolean isPastDeadline() {
        return timeoutMillis > 0 && startedAtMillis > 0
                && System.currentTimeMillis() - startedAtMillis > timeoutMillis;
    }

    /** 是否已到达终态（SUCCEEDED/FAILED/CANCELLED/TIMEOUT）。 */
    public boolean isTerminal() {
        TaskStatus s = status;
        return s == TaskStatus.SUCCEEDED || s == TaskStatus.FAILED
                || s == TaskStatus.CANCELLED || s == TaskStatus.TIMEOUT;
    }
}
