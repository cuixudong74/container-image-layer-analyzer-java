package com.github.cuixudong74.containerimagelayeranalyzerjava.task;

import com.github.cuixudong74.containerimagelayeranalyzerjava.analyze.LayerAnalyzer;
import com.github.cuixudong74.containerimagelayeranalyzerjava.analyze.ProgressTracker;
import com.github.cuixudong74.containerimagelayeranalyzerjava.analyze.ScanCancelledException;
import com.github.cuixudong74.containerimagelayeranalyzerjava.config.AnalyzerProperties;
import com.github.cuixudong74.containerimagelayeranalyzerjava.input.ImageSourceFactory;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.ImageReport;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.TaskStatus;
import com.github.cuixudong74.containerimagelayeranalyzerjava.optimize.ImageOptimizer;
import com.github.cuixudong74.containerimagelayeranalyzerjava.optimize.OptimizeResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/**
 * 任务管理器。
 *
 * <p>所有任务共享固定大小的执行线程池；调度以"一层"为分片在就绪任务间
 * 轮转（round-robin），单个超大任务只会占用自己的分片，不会长期饿死
 * 其他任务。取消/超时在条目级与分片级都会被检查，触发后立即中止会话、
 * 关闭输入、清理临时产物。任务状态可区分：
 * PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED/TIMEOUT。
 */
public class ScanTaskManager implements AutoCloseable {
    private final AnalyzerProperties properties;
    private final LayerAnalyzer analyzer;
    private final ImageOptimizer optimizer;

    private final Map<String, ScanTask> tasks = new LinkedHashMap<>();
    private final Deque<ScanTask> ready = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean shutdown;

    public ScanTaskManager(AnalyzerProperties properties) {
        this.properties = properties;
        this.analyzer = new LayerAnalyzer(properties);
        this.optimizer = new ImageOptimizer(properties);
        int n = Math.max(1, properties.getWorkerThreads());
        for (int i = 0; i < n; i++) {
            Thread t = new Thread(this::workerLoop, "scan-worker-" + i);
            t.setDaemon(true);
            workers.add(t);
            t.start();
        }
    }

    public String submitScan(Path input) {
        return submit(new ScanTask(newId(), ScanTask.Kind.SCAN, input, null,
                properties.getTaskTimeoutMillis()));
    }

    public String submitOptimize(Path input, Path output) {
        return submit(new ScanTask(newId(), ScanTask.Kind.OPTIMIZE, input, output,
                properties.getTaskTimeoutMillis()));
    }

    private String submit(ScanTask task) {
        lock.lock();
        try {
            if (shutdown) {
                throw new IllegalStateException("task manager is shut down");
            }
            tasks.put(task.getId(), task);
            ready.addLast(task);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
        return task.getId();
    }

    public ScanTask getTask(String id) {
        lock.lock();
        try {
            return tasks.get(id);
        } finally {
            lock.unlock();
        }
    }

    /** 按提交顺序列出任务（稳定顺序）。 */
    public List<ScanTask> listTasks() {
        lock.lock();
        try {
            return List.copyOf(tasks.values());
        } finally {
            lock.unlock();
        }
    }

    /**
     * 请求取消。任务在条目级/分片级检查点尽快停止；
     * 已终态任务返回 false。
     */
    public boolean cancel(String id) {
        ScanTask task = getTask(id);
        if (task == null || task.isTerminal()) {
            return false;
        }
        task.cancelRequested.set(true);
        lock.lock();
        try {
            if (task.getStatus() == TaskStatus.PENDING) {
                // 尚未开始：直接终态，worker 取到时会跳过
                task.markCancelled();
            }
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
        return true;
    }

    /** 获取任务报告（仅 SUCCEEDED 后可用，否则返回 null）。 */
    public ImageReport getReport(String id) {
        ScanTask t = getTask(id);
        return t == null ? null : t.getReportInternal();
    }

    /** 获取优化结果（仅 SUCCEEDED 后可用，否则返回 null）。 */
    public OptimizeResult getOptimizeResult(String id) {
        ScanTask t = getTask(id);
        return t == null ? null : t.getOptimizeResultInternal();
    }

    @Override
    public void close() {
        shutdown = true;
        lock.lock();
        try {
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
        for (Thread w : workers) {
            try {
                w.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // ---- 调度 ----

    private void workerLoop() {
        while (!shutdown) {
            ScanTask task;
            lock.lock();
            try {
                while (ready.isEmpty() && !shutdown) {
                    try {
                        notEmpty.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (shutdown) {
                    return;
                }
                task = ready.pollFirst();
            } finally {
                lock.unlock();
            }
            if (task == null) {
                continue;
            }
            boolean requeue = runOneSlice(task);
            if (requeue) {
                lock.lock();
                try {
                    ready.addLast(task);
                    notEmpty.signal();
                } finally {
                    lock.unlock();
                }
            }
        }
    }

    /**
     * 为任务执行一个分片；返回 true 表示任务未完成、需要重新排队。
     */
    private boolean runOneSlice(ScanTask task) {
        if (task.isTerminal()) {
            cleanup(task);
            return false;
        }
        try {
            if (task.getStatus() == TaskStatus.PENDING) {
                startTask(task);
            }
            boolean more = task.getKind() == ScanTask.Kind.SCAN
                    ? analyzer.runAnalysisSlice(task.analysisSession)
                    : optimizer.runOptimizeSlice(task.optimizeSession);
            if (more) {
                return true;
            }
            if (task.getKind() == ScanTask.Kind.SCAN) {
                task.markSucceeded(analyzer.finishAnalysis(task.analysisSession));
            } else {
                task.markSucceeded(optimizer.finishOptimize(task.optimizeSession));
            }
            cleanup(task);
            return false;
        } catch (ScanCancelledException e) {
            abort(task);
            if (task.isPastDeadline()) {
                task.markTimeout();
            } else {
                task.markCancelled();
            }
            return false;
        } catch (Exception e) {
            abort(task);
            task.markFailed(String.valueOf(e.getMessage()));
            return false;
        }
    }

    private void startTask(ScanTask task) throws IOException {
        task.source = ImageSourceFactory.open(task.getInputPath());
        BooleanSupplier cancelled = () -> task.cancelRequested.get() || task.isPastDeadline();
        if (task.getKind() == ScanTask.Kind.SCAN) {
            task.analysisSession = analyzer.beginAnalysis(task.source, null, cancelled);
            task.markRunning(task.analysisSession.getTracker());
        } else {
            task.optimizeSession = optimizer.beginOptimize(task.source,
                    task.getOutputPath(), cancelled);
            task.markRunning(new ProgressTracker(-1));
        }
    }

    /** 正常结束后的资源释放。 */
    private void cleanup(ScanTask task) {
        if (task.source != null) {
            try {
                task.source.close();
            } catch (IOException ignored) {
                // 关闭失败不影响终态
            }
            task.source = null;
        }
        task.analysisSession = null;
        task.optimizeSession = null;
    }

    /** 取消/超时/失败后的中止：清理优化临时产物并释放输入。 */
    private void abort(ScanTask task) {
        if (task.optimizeSession != null) {
            optimizer.abortOptimize(task.optimizeSession);
        }
        cleanup(task);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
