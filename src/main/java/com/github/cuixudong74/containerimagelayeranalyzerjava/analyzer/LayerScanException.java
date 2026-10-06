package com.github.cuixudong74.containerimagelayeranalyzerjava.analyzer;

import com.github.cuixudong74.containerimagelayeranalyzerjava.model.LayerIssue;

/** Fatal failure of one layer; aborts the scan. Carries the machine-readable issue. */
public class LayerScanException extends RuntimeException {
    private final LayerIssue issue;

    public LayerScanException(LayerIssue issue, Throwable cause) {
        super(issue.message(), cause);
        this.issue = issue;
    }

    public LayerIssue getIssue() {
        return issue;
    }
}
