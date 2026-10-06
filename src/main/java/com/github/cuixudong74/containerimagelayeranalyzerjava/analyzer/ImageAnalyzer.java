package com.github.cuixudong74.containerimagelayeranalyzerjava.analyzer;

import com.github.cuixudong74.containerimagelayeranalyzerjava.config.AnalyzerProperties;
import com.github.cuixudong74.containerimagelayeranalyzerjava.input.ImageSource;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.EntryType;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.ImageReport;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Scans an image source layer by layer and produces a deterministic report.
 *
 * <p>Memory is bounded: entry content is streamed through a fixed-size buffer
 * (hashing on the fly), and only the top-N largest entries per layer are kept
 * for the report. The merged filesystem view holds metadata only.
 *
 * <p>A layer that fails to parse aborts the scan with a
 * {@link LayerScanException} identifying the layer and the reason; no partial
 * report is produced.
 */
public final class ImageAnalyzer {

    /** Whiteout prefix defined by the OCI/image spec. */
    static final String WHITEOUT_PREFIX = ".wh.";
    /** Opaque-directory marker name. */
    static final String OPAQUE_MARKER = ".wh..wh..opq";
    /** Number of top paths/layers kept in summary lists. */
    static final int TOP_N = 20;

    private final AnalyzerProperties properties;

    public ImageAnalyzer(AnalyzerProperties properties) {
        this.properties = properties;
    }

    public ImageReport analyze(ImageSource source, ProgressListener progress) {
        try (ScanSession session = new ScanSession(source, properties)) {
            while (session.hasNextLayer()) {
                session.scanNextLayer(progress);
            }
            return session.finish();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static EntryType typeOf(char typeFlag) {
        return switch (typeFlag) {
            case '0', '\0' -> EntryType.FILE;
            case '1' -> EntryType.HARDLINK;
            case '2' -> EntryType.SYMLINK;
            case '5' -> EntryType.DIRECTORY;
            default -> EntryType.OTHER;
        };
    }

    static String parentOf(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }
}
