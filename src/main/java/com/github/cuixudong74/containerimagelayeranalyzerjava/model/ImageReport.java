package com.github.cuixudong74.containerimagelayeranalyzerjava.model;

import java.util.List;
import java.util.Map;

/** Whole-image report: per-layer breakdown plus totals and redundancy summary. */
public record ImageReport(
        String imageId,
        List<LayerReport> layers,
        long totalBytes,
        Map<EntryType, Long> countByType,
        Map<EntryType, Long> bytesByType,
        long finalFilesystemBytes,
        long reclaimableBytes,
        long duplicateContentBytes,
        List<PathBytes> topReclaimablePaths,
        List<LayerBytes> topReclaimableLayers,
        List<LayerIssue> issues) {

    public record PathBytes(String path, long bytes) {
    }

    public record LayerBytes(int layerIndex, String layerId, long bytes) {
    }
}
