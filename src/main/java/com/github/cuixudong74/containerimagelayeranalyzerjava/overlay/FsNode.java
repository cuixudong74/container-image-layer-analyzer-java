package com.github.cuixudong74.containerimagelayeranalyzerjava.overlay;

import com.github.cuixudong74.containerimagelayeranalyzerjava.model.EntryType;

/** One node of the reconstructed final filesystem. */
public record FsNode(
        String path,
        EntryType type,
        long size,
        String linkTarget,
        String sha256,
        int originLayerIndex) {
}
