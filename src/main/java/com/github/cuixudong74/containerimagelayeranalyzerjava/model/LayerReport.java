package com.github.cuixudong74.containerimagelayeranalyzerjava.model;

import java.util.List;
import java.util.Map;

/** Per-layer size report. Counts/bytes are broken down by entry type. */
public record LayerReport(
        int layerIndex,
        String layerId,
        long totalBytes,
        Map<EntryType, Long> countByType,
        Map<EntryType, Long> bytesByType,
        long redundantBytes,
        List<LayerEntry> entries,
        List<LayerIssue> issues) {
}
