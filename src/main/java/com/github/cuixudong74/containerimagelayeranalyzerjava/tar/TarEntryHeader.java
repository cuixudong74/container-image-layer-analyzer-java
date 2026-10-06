package com.github.cuixudong74.containerimagelayeranalyzerjava.tar;

/** tar 条目头（仅保留分析所需字段）。 */
public record TarEntryHeader(String name, long size, char typeFlag, String linkName) {
    public static final char TYPE_REGULAR = '0';
    public static final char TYPE_REGULAR_ALT = '\0';
    public static final char TYPE_HARDLINK = '1';
    public static final char TYPE_SYMLINK = '2';
    public static final char TYPE_DIRECTORY = '5';
}
