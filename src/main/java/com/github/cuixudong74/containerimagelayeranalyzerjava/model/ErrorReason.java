package com.github.cuixudong74.containerimagelayeranalyzerjava.model;

/** 层处理失败/跳过的可区分原因。 */
public enum ErrorReason {
    TRUNCATED_ARCHIVE,
    CHECKSUM_MISMATCH,
    PATH_TRAVERSAL,
    INVALID_METADATA,
    IO_ERROR,
    CANCELLED,
    TIMEOUT
}
