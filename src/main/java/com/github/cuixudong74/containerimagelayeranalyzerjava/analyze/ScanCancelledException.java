package com.github.cuixudong74.containerimagelayeranalyzerjava.analyze;

/** 扫描被取消或超时。 */
public class ScanCancelledException extends RuntimeException {
    private final boolean timeout;

    public ScanCancelledException(boolean timeout) {
        super(timeout ? "scan timed out" : "scan cancelled");
        this.timeout = timeout;
    }

    public boolean isTimeout() {
        return timeout;
    }
}
