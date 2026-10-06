package com.github.cuixudong74.containerimagelayeranalyzerjava.model;

/** Lifecycle states of a scan/optimize task. */
public enum TaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMEOUT
}
