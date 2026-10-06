package com.github.cuixudong74.containerimagelayeranalyzerjava.model;

/** One scanned entry inside a layer, in deterministic (path-sorted) reporting order. */
public record LayerEntry(
        String path,
        EntryType type,
        long size,
        String linkTarget,
        String sha256,
        boolean redundant) {
}
