package com.github.cuixudong74.containerimagelayeranalyzerjava.model;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 单层体积报告。 */
public class LayerReport {
    public enum Status { OK, SKIPPED, FAILED }

    private final int layerIndex;
    private final String layerId;
    private Status status = Status.OK;
    private final Map<EntryType, Long> entryCounts = new EnumMap<>(EntryType.class);
    private final Map<EntryType, Long> entryBytes = new EnumMap<>(EntryType.class);
    private final List<LayerError> errors = new java.util.ArrayList<>();
    private long totalBytes;

    public LayerReport(int layerIndex, String layerId) {
        this.layerIndex = layerIndex;
        this.layerId = layerId;
    }

    public int getLayerIndex() { return layerIndex; }
    public String getLayerId() { return layerId; }
    public Status getStatus() { return status; }
    public void setStatus(Status s) { this.status = s; }
    public Map<EntryType, Long> getEntryCounts() { return entryCounts; }
    public Map<EntryType, Long> getEntryBytes() { return entryBytes; }
    public List<LayerError> getErrors() { return errors; }
    public long getTotalBytes() { return totalBytes; }

    public void addEntry(EntryType type, long bytes) {
        entryCounts.merge(type, 1L, Long::sum);
        entryBytes.merge(type, bytes, Long::sum);
        totalBytes += bytes;
    }
}
