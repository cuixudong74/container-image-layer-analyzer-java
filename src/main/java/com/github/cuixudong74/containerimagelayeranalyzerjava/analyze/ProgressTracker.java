package com.github.cuixudong74.containerimagelayeranalyzerjava.analyze;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 线程安全的扫描进度。扫描期间可随时查询。
 * 所有字段单调更新，读取端无需加锁。
 */
public class ProgressTracker {
    private final long totalBytes;
    private final AtomicLong processedBytes = new AtomicLong();
    private final AtomicLong processedEntries = new AtomicLong();
    private volatile int currentLayerIndex = -1;
    private volatile String currentLayerId = "";
    private volatile int totalLayers;

    public ProgressTracker(long totalBytes) {
        this.totalBytes = totalBytes;
    }

    public void setTotalLayers(int n) {
        this.totalLayers = n;
    }

    public void onLayerStart(int layerIndex, String layerId) {
        this.currentLayerIndex = layerIndex;
        this.currentLayerId = layerId;
    }

    public void onBytes(long n) {
        processedBytes.addAndGet(n);
    }

    public void onEntry() {
        processedEntries.incrementAndGet();
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public long getProcessedBytes() {
        return processedBytes.get();
    }

    public long getProcessedEntries() {
        return processedEntries.get();
    }

    public int getCurrentLayerIndex() {
        return currentLayerIndex;
    }

    public String getCurrentLayerId() {
        return currentLayerId;
    }

    public int getTotalLayers() {
        return totalLayers;
    }

    /** 0.0 ~ 1.0；总量未知时返回 -1。 */
    public double fraction() {
        if (totalBytes <= 0) {
            return -1;
        }
        return Math.min(1.0, (double) processedBytes.get() / totalBytes);
    }
}
