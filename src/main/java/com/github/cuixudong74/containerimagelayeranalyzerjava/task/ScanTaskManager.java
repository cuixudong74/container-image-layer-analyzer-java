package com.github.cuixudong74.containerimagelayeranalyzerjava.task;

import com.github.cuixudong74.containerimagelayeranalyzerjava.analyzer.ProgressListener;
import com.github.cuixudong74.containerimagelayeranalyzerjava.analyzer.ScanSession;
import com.github.cuixudong74.containerimagelayeranalyzerjava.config.AnalyzerProperties;
import com.github.cuixudong74.containerimagelayeranalyzerjava.input.ImageSource;
import com.github.cuixudong74.containerimagelayeranalyzerjava.input.ImageSourceFactory;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.TaskSnapshot;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.TaskStatus;
import com.github.cuixudong74.containerimagelayeranalyzerjava.optimize.ImageOptimizer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared executor for scan/optimize tasks.
 *
 * <p>Fairness: each task is split into per-layer work units that are
 * re-submitted to the common pool one at a time, so units of different tasks
 * interleave and one huge image cannot monopolize the workers.
 *
 * <p>Cancellation and timeouts are cooperative: a flag is set and the running
 * unit aborts at the next checkpoint (every buffered read). On any terminal
 * transition the plan is closed, releasing streams and deleting intermediate
 * artifacts, so nothing partial is left behind.
 */
public final class ScanTaskManager implements AutoCloseable {

    private final AnalyzerProperties properties;
    private final ExecutorService workers;
    private final ScheduledExecutorService scheduler;
    private final Map<String, ManagedTask> tasks = new ConcurrentHashMap<>();

    public ScanTaskManager(AnalyzerProperties properties) {
        this.properties = properties;
        this.workers = Executors.newFixedThreadPool(properties.getWorkerThreads(), r -> {
            Thread t = new Thread(r, "cila-worker");
            t.setDaemon(true);
            return t;
        });
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cila-timeout");
            t.setDaemon(true);
            return t;
        });
    }

    /** Submits a scan task; returns its id immediately. */
    public String submitScan(Path imagePath) {
        return submit(t -> {
            ImageSource source = ImageSourceFactory.open(imagePath, properties.getBufferSizeBytes(),
                    Path.of(properties.getTempDir()));
            return new ScanPlan(source, progressOf(t));
        });
    }

    /** Submits an optimize task writing to outputDir; returns its id. */
    public String submitOptimize(Path imagePath, Path outputDir) {
        return submit(t -> {
            ImageSource source = ImageSourceFactory.open(imagePath, properties.getBufferSizeBytes(),
                    Path.of(properties.getTempDir()));
            return new ImageOptimizer(properties).createPlan(source, outputDir, progressOf(t));
        });
    }

    public Optional<TaskSnapshot> status(String taskId) {
        ManagedTask t = tasks.get(taskId);
        return t == null ? Optional.empty() : Optional.of(t.snapshot());
    }

    public List<TaskSnapshot> listTasks() {
        List<TaskSnapshot> out = new ArrayList<>();
        for (ManagedTask t : tasks.values()) {
            out.add(t.snapshot());
        }
        out.sort((a, b) -> a.taskId().compareTo(b.taskId()));
        return out;
    }

    /** Requests cancellation; returns false if the task is unknown or already finished. */
    public boolean cancel(String taskId) {
        ManagedTask t = tasks.get(taskId);
        if (t == null || t.isTerminal()) {
            return false;
        }
        t.cancelRequested.set(true);
        return true;
    }

    /** Fetches the finished result (ImageReport), if completed. */
    public Optional<Object> result(String taskId) {
        ManagedTask t = tasks.get(taskId);
        if (t == null || t.status.get() != TaskStatus.COMPLETED) {
            return Optional.empty();
        }
        return Optional.ofNullable(t.result);
    }

    @Override
    public void close() {
        for (ManagedTask t : tasks.values()) {
            t.cancelRequested.set(true);
        }
        workers.shutdown();
        scheduler.shutdown();
        try {
            workers.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------

    private String submit(PlanFactory factory) {
        String id = UUID.randomUUID().toString();
        ManagedTask t = new ManagedTask(id);
        tasks.put(id, t);
        t.timeoutFuture = scheduler.schedule(() -> {
            if (!t.isTerminal()) {
                t.timedOut.set(true);
                t.cancelRequested.set(true);
            }
        }, properties.getTaskTimeoutMillis(), TimeUnit.MILLISECONDS);
        workers.execute(() -> {
            try {
                t.plan = factory.create(t);
                t.layersTotal = t.plan.totalUnits();
            } catch (Throwable e) {
                fail(t, e);
                return;
            }
            scheduleNext(t);
        });
        return id;
    }

    private void scheduleNext(ManagedTask t) {
        workers.execute(() -> {
            if (t.cancelRequested.get()) {
                finishCancelled(t);
                return;
            }
            t.status.compareAndSet(TaskStatus.PENDING, TaskStatus.RUNNING);
            try {
                boolean more = t.plan.step();
                if (t.cancelRequested.get()) {
                    finishCancelled(t);
                } else if (more) {
                    scheduleNext(t);
                } else {
                    t.result = t.plan.result();
                    t.status.set(TaskStatus.COMPLETED);
                    cleanup(t);
                }
            } catch (CancellationException e) {
                finishCancelled(t);
            } catch (Throwable e) {
                fail(t, e);
            }
        });
    }

    private void finishCancelled(ManagedTask t) {
        t.status.set(t.timedOut.get() ? TaskStatus.TIMEOUT : TaskStatus.CANCELLED);
        cleanup(t);
    }

    private void fail(ManagedTask t, Throwable e) {
        t.error = e.getMessage() == null ? e.toString() : e.getMessage();
        t.status.set(TaskStatus.FAILED);
        cleanup(t);
    }

    private void cleanup(ManagedTask t) {
        if (t.timeoutFuture != null) {
            t.timeoutFuture.cancel(false);
        }
        if (t.plan != null) {
            try {
                t.plan.close();
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
    }

    private interface PlanFactory {
        TaskPlan create(ManagedTask task) throws Exception;
    }

    // ------------------------------------------------------------------

    private final class ManagedTask {
        final String id;
        final AtomicReference<TaskStatus> status = new AtomicReference<>(TaskStatus.PENDING);
        final AtomicBoolean cancelRequested = new AtomicBoolean();
        final AtomicBoolean timedOut = new AtomicBoolean();
        final AtomicInteger layersCompleted = new AtomicInteger();
        final AtomicLong bytesScanned = new AtomicLong();
        volatile String currentLayer = "";
        volatile int layersTotal = -1;
        volatile TaskPlan plan;
        volatile Object result;
        volatile String error;
        volatile java.util.concurrent.ScheduledFuture<?> timeoutFuture;

        ManagedTask(String id) {
            this.id = id;
        }

        boolean isTerminal() {
            TaskStatus s = status.get();
            return s == TaskStatus.COMPLETED || s == TaskStatus.FAILED
                    || s == TaskStatus.CANCELLED || s == TaskStatus.TIMEOUT;
        }

        TaskSnapshot snapshot() {
            return new TaskSnapshot(id, status.get(), layersTotal, layersCompleted.get(),
                    bytesScanned.get(), currentLayer, error);
        }
    }

    /** Scan task: one work unit per layer. */
    private final class ScanPlan implements TaskPlan {
        private final ScanSession session;
        private final ProgressListener progress;
        private com.github.cuixudong74.containerimagelayeranalyzerjava.model.ImageReport report;

        ScanPlan(ImageSource source, ProgressListener progress) {
            this.session = new ScanSession(source, properties);
            this.progress = progress;
        }

        @Override
        public int totalUnits() {
            return session.totalLayers();
        }

        @Override
        public boolean step() {
            if (session.hasNextLayer()) {
                session.scanNextLayer(progress);
            }
            if (!session.hasNextLayer()) {
                report = session.finish();
                return false;
            }
            return true;
        }

        @Override
        public Object result() {
            return report;
        }

        @Override
        public void close() throws Exception {
            session.close();
        }
    }

    private ProgressListener progressOf(ManagedTask t) {
        return new ProgressListener() {
            @Override
            public void onLayerStart(int layerIndex, String layerId) {
                t.currentLayer = layerId;
            }

            @Override
            public void onBytesScanned(long bytes) {
                t.bytesScanned.addAndGet(bytes);
            }

            @Override
            public void onLayerDone(int layerIndex) {
                t.layersCompleted.incrementAndGet();
            }

            @Override
            public boolean isCancelled() {
                return t.cancelRequested.get();
            }
        };
    }
}
