package com.github.cuixudong74.containerimagelayeranalyzerjava.tar;

/** Parsed tar entry header (metadata only; content is streamed separately). */
public record TarEntryHeader(
        String name,
        long size,
        char typeFlag,
        String linkName,
        long mtime) {
}
