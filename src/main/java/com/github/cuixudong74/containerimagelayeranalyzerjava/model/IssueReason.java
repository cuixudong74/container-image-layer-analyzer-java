package com.github.cuixudong74.containerimagelayeranalyzerjava.model;

/** Machine-distinguishable reasons for skipping/failing a layer or entry. */
public enum IssueReason {
    TRUNCATED_ARCHIVE,
    CHECKSUM_MISMATCH,
    PATH_TRAVERSAL,
    INVALID_METADATA,
    UNSUPPORTED_FORMAT,
    IO_ERROR,
    CANCELLED,
    TIMEOUT
}
