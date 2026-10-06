package com.github.cuixudong74.containerimagelayeranalyzerjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Tunables bounding memory use, concurrency and task lifetime. */
@ConfigurationProperties(prefix = "analyzer")
public class AnalyzerProperties {
    /** I/O buffer size per stream; bounds per-layer memory. */
    private int bufferSizeBytes = 64 * 1024;
    /** Worker threads shared by all scan tasks. */
    private int workerThreads = 2;
    /** Max wall-clock time per task before it is failed with TIMEOUT. */
    private long taskTimeoutMillis = 10 * 60 * 1000L;
    /** Directory for temporary intermediate output (cleaned up on cancel/failure). */
    private String tempDir = System.getProperty("java.io.tmpdir");
    /** Max number of entries kept in the report per layer (top-N by size kept; rest aggregated). */
    private int maxReportEntriesPerLayer = 10_000;

    public int getBufferSizeBytes() { return bufferSizeBytes; }
    public void setBufferSizeBytes(int v) { this.bufferSizeBytes = v; }
    public int getWorkerThreads() { return workerThreads; }
    public void setWorkerThreads(int v) { this.workerThreads = v; }
    public long getTaskTimeoutMillis() { return taskTimeoutMillis; }
    public void setTaskTimeoutMillis(long v) { this.taskTimeoutMillis = v; }
    public String getTempDir() { return tempDir; }
    public void setTempDir(String v) { this.tempDir = v; }
    public int getMaxReportEntriesPerLayer() { return maxReportEntriesPerLayer; }
    public void setMaxReportEntriesPerLayer(int v) { this.maxReportEntriesPerLayer = v; }
}
