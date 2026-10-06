package com.github.cuixudong74.containerimagelayeranalyzerjava.model;

/** Kinds of entries that can appear in an image layer. */
public enum EntryType {
    FILE,
    DIRECTORY,
    SYMLINK,
    HARDLINK,
    /** Whiteout marker (".wh." prefixed) hiding a lower-layer path. */
    WHITEOUT,
    /** Opaque directory marker (".wh..wh..opq"). */
    OPAQUE_MARKER,
    OTHER
}
