package com.github.cuixudong74.containerimagelayeranalyzerjava.model;

/** Point-in-time, thread-safe view of a task for progress queries. */
public record TaskSnapshot(
        String taskId,
        TaskStatus status,
        int layersTotal,
        int layersCompleted,
        long bytesScanned,
        String currentLayer,
        String errorMessage) {
}
