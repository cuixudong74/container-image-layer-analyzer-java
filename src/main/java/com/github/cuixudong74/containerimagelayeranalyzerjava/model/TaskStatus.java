package com.github.cuixudong74.containerimagelayeranalyzerjava.model;

/** 任务状态，可区分。 */
public enum TaskStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMEOUT
}
