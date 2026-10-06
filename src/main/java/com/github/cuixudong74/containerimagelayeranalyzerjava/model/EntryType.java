package com.github.cuixudong74.containerimagelayeranalyzerjava.model;

/** 层内条目类型。 */
public enum EntryType {
    REGULAR,
    DIRECTORY,
    SYMLINK,
    HARDLINK,
    WHITEOUT,
    OPAQUE_MARKER,
    OTHER
}
