package com.github.cuixudong74.containerimagelayeranalyzerjava.tar;

import com.github.cuixudong74.containerimagelayeranalyzerjava.model.ErrorReason;

/** tar 解析失败，携带可区分原因。 */
public class TarParseException extends Exception {
    private final ErrorReason reason;

    public TarParseException(ErrorReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public TarParseException(ErrorReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public ErrorReason getReason() {
        return reason;
    }
}
