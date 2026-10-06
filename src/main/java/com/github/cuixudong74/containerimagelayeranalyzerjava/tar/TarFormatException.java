package com.github.cuixudong74.containerimagelayeranalyzerjava.tar;

import com.github.cuixudong74.containerimagelayeranalyzerjava.model.IssueReason;

/** Raised when a tar stream is malformed; carries a machine-readable reason. */
public class TarFormatException extends Exception {
    private final IssueReason reason;
    private final String entryPath;

    public TarFormatException(IssueReason reason, String entryPath, String message) {
        super(message);
        this.reason = reason;
        this.entryPath = entryPath;
    }

    public IssueReason getReason() { return reason; }
    public String getEntryPath() { return entryPath; }
}
