package com.github.cuixudong74.containerimagelayeranalyzerjava.tar;

import com.github.cuixudong74.containerimagelayeranalyzerjava.model.IssueReason;
import com.github.cuixudong74.containerimagelayeranalyzerjava.testutil.TarBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TarReaderTest {

    @Test
    void readsEntriesInOrderWithContent() throws Exception {
        byte[] tar = new TarBuilder()
                .file("a.txt", "hello")
                .dir("d")
                .file("d/b.txt", "world!")
                .symlink("link", "a.txt")
                .hardlink("hard", "a.txt")
                .build();
        try (TarReader r = new TarReader(new ByteArrayInputStream(tar))) {
            List<String> names = new ArrayList<>();
            List<String> contents = new ArrayList<>();
            TarEntryHeader h;
            while ((h = r.nextEntry()) != null) {
                names.add(h.name() + ":" + h.typeFlag());
                contents.add(new String(r.currentEntryStream().readAllBytes()));
            }
            assertEquals(List.of("a.txt:0", "d:5", "d/b.txt:0", "link:2", "hard:1"), names);
            assertEquals("hello", contents.get(0));
            assertEquals("world!", contents.get(2));
        }
    }

    @Test
    void normalizesDotSlashPrefixes() throws Exception {
        byte[] tar = new TarBuilder().file("./x/y.txt", "z").build();
        try (TarReader r = new TarReader(new ByteArrayInputStream(tar))) {
            TarEntryHeader h = r.nextEntry();
            assertEquals("x/y.txt", h.name());
        }
    }

    @Test
    void rejectsPathTraversal() throws Exception {
        byte[] tar = new TarBuilder().file("../evil", "x").build();
        try (TarReader r = new TarReader(new ByteArrayInputStream(tar))) {
            TarFormatException e = assertThrows(TarFormatException.class, r::nextEntry);
            assertEquals(IssueReason.PATH_TRAVERSAL, e.getReason());
        }
    }

    @Test
    void rejectsAbsolutePath() throws Exception {
        byte[] tar = new TarBuilder().file("/etc/passwd", "x").build();
        try (TarReader r = new TarReader(new ByteArrayInputStream(tar))) {
            TarFormatException e = assertThrows(TarFormatException.class, r::nextEntry);
            assertEquals(IssueReason.PATH_TRAVERSAL, e.getReason());
        }
    }

    @Test
    void rejectsTruncatedArchive() throws Exception {
        byte[] tar = new TarBuilder().file("a.txt", "hello").build();
        byte[] truncated = Arrays.copyOf(tar, tar.length - 1024); // drop end markers
        try (TarReader r = new TarReader(new ByteArrayInputStream(truncated))) {
            assertNotNull(r.nextEntry());
            r.currentEntryStream().readAllBytes();
            TarFormatException e = assertThrows(TarFormatException.class, r::nextEntry);
            assertEquals(IssueReason.TRUNCATED_ARCHIVE, e.getReason());
        }
    }

    @Test
    void rejectsTruncatedContent() throws Exception {
        byte[] tar = new TarBuilder().file("a.txt", "hello").build();
        byte[] truncated = Arrays.copyOf(tar, 512 + 2); // header + partial content
        try (TarReader r = new TarReader(new ByteArrayInputStream(truncated))) {
            assertNotNull(r.nextEntry());
            assertThrows(IOException.class, () -> r.currentEntryStream().readAllBytes());
        }
    }

    @Test
    void rejectsChecksumMismatch() throws Exception {
        byte[] tar = new TarBuilder().file("a.txt", "hello").build();
        tar[10] = 'Z'; // corrupt name without fixing checksum
        try (TarReader r = new TarReader(new ByteArrayInputStream(tar))) {
            TarFormatException e = assertThrows(TarFormatException.class, r::nextEntry);
            assertEquals(IssueReason.CHECKSUM_MISMATCH, e.getReason());
        }
    }

    @Test
    void skipsUnconsumedEntries() throws Exception {
        byte[] tar = new TarBuilder()
                .file("a.txt", "hello")
                .file("b.txt", "world")
                .build();
        try (TarReader r = new TarReader(new ByteArrayInputStream(tar))) {
            assertEquals("a.txt", r.nextEntry().name());
            // do not read content of a.txt
            assertEquals("b.txt", r.nextEntry().name());
            assertEquals("world", new String(r.currentEntryStream().readAllBytes()));
            assertNull(r.nextEntry());
        }
    }
}
