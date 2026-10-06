package com.github.cuixudong74.containerimagelayeranalyzerjava.report;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.ImageReport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Serializes reports deterministically: record fields are declared in a fixed
 * order, map keys are sorted, and no timestamps or random ids are emitted, so
 * identical inputs produce byte-identical JSON.
 */
public final class ReportWriter {

    private final ObjectMapper mapper;

    public ReportWriter() {
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    /** Serializes to a byte-identical JSON string for identical inputs. */
    public String toJson(ImageReport report) {
        try {
            return mapper.writeValueAsString(report);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Atomically writes the report: content goes to a temporary file in the
     * target directory first and is then renamed, so a crash or cancellation
     * never leaves a partially written report behind.
     */
    public void writeAtomically(ImageReport report, Path target) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, target.getFileName().toString(), ".tmp");
        boolean ok = false;
        try {
            Files.writeString(tmp, toJson(report), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            ok = true;
        } finally {
            if (!ok) {
                Files.deleteIfExists(tmp);
            }
        }
    }
}
