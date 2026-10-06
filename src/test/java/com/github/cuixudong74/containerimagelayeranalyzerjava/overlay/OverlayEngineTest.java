package com.github.cuixudong74.containerimagelayeranalyzerjava.overlay;

import com.github.cuixudong74.containerimagelayeranalyzerjava.model.EntryType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OverlayEngineTest {

    private static FsNode file(String path, long size, int layer) {
        return new FsNode(path, EntryType.FILE, size, null, null, layer);
    }

    private static FsNode dir(String path, int layer) {
        return new FsNode(path, EntryType.DIRECTORY, 0, null, null, layer);
    }

    @Test
    void laterLayerOverridesSamePath() {
        OverlayEngine e = new OverlayEngine();
        e.apply(0, "a.txt", file("a.txt", 100, 0));
        e.apply(1, "a.txt", file("a.txt", 50, 1));
        Map<String, FsNode> fs = e.finalFilesystem();
        assertEquals(50, fs.get("a.txt").size());
        assertEquals(1, fs.get("a.txt").originLayerIndex());
        assertEquals(100, e.totalReclaimableBytes());
        assertEquals(100L, e.reclaimableByLayer().get(0));
        assertEquals(100L, e.reclaimableByPath().get("a.txt"));
    }

    @Test
    void whiteoutRemovesFile() {
        OverlayEngine e = new OverlayEngine();
        e.apply(0, "gone.txt", file("gone.txt", 200, 0));
        e.apply(0, "kept.txt", file("kept.txt", 10, 0));
        e.applyWhiteout(1, "gone.txt");
        assertFalse(e.finalFilesystem().containsKey("gone.txt"));
        assertTrue(e.finalFilesystem().containsKey("kept.txt"));
        assertEquals(200, e.totalReclaimableBytes());
    }

    @Test
    void whiteoutRemovesDirectorySubtree() {
        OverlayEngine e = new OverlayEngine();
        e.apply(0, "d", dir("d", 0));
        e.apply(0, "d/a.txt", file("d/a.txt", 10, 0));
        e.apply(0, "d/sub/b.txt", file("d/sub/b.txt", 20, 0));
        e.apply(0, "other.txt", file("other.txt", 5, 0));
        e.applyWhiteout(1, "d");
        Map<String, FsNode> fs = e.finalFilesystem();
        assertEquals(Map.of("other.txt", fs.get("other.txt")), fs);
        assertEquals(30, e.totalReclaimableBytes());
    }

    @Test
    void whiteoutOfMissingPathIsNoOp() {
        OverlayEngine e = new OverlayEngine();
        e.apply(0, "a.txt", file("a.txt", 1, 0));
        e.applyWhiteout(1, "nope.txt");
        assertEquals(1, e.finalFilesystem().size());
        assertEquals(0, e.totalReclaimableBytes());
    }

    @Test
    void opaqueDirHidesLowerChildrenButKeepsUpper() {
        OverlayEngine e = new OverlayEngine();
        e.apply(0, "d", dir("d", 0));
        e.apply(0, "d/lower.txt", file("d/lower.txt", 30, 0));
        e.applyOpaqueDir(1, "d");
        e.apply(1, "d/upper.txt", file("d/upper.txt", 7, 1));
        Map<String, FsNode> fs = e.finalFilesystem();
        assertFalse(fs.containsKey("d/lower.txt"));
        assertTrue(fs.containsKey("d/upper.txt"));
        assertTrue(fs.containsKey("d")); // dir itself survives
        assertEquals(30, e.totalReclaimableBytes());
    }

    @Test
    void directoryMergeKeepsLowerChildren() {
        OverlayEngine e = new OverlayEngine();
        e.apply(0, "d", dir("d", 0));
        e.apply(0, "d/child.txt", file("d/child.txt", 12, 0));
        e.apply(1, "d", dir("d", 1)); // upper layer re-creates the dir
        assertTrue(e.finalFilesystem().containsKey("d/child.txt"));
        assertEquals(0, e.totalReclaimableBytes());
    }

    @Test
    void fileReplacingDirectoryRemovesSubtree() {
        OverlayEngine e = new OverlayEngine();
        e.apply(0, "x", dir("x", 0));
        e.apply(0, "x/inner.txt", file("x/inner.txt", 40, 0));
        e.apply(1, "x", file("x", 3, 1));
        Map<String, FsNode> fs = e.finalFilesystem();
        assertEquals(1, fs.size());
        assertEquals(EntryType.FILE, fs.get("x").type());
        assertEquals(40, e.totalReclaimableBytes());
    }

    @Test
    void doubleOverrideCountsBothGenerations() {
        OverlayEngine e = new OverlayEngine();
        e.apply(0, "f", file("f", 10, 0));
        e.apply(1, "f", file("f", 20, 1));
        e.apply(2, "f", file("f", 30, 2));
        assertEquals(30, e.finalFilesystem().get("f").size());
        assertEquals(30, e.totalReclaimableBytes()); // 10 + 20
        assertEquals(10L, e.reclaimableByLayer().get(0));
        assertEquals(20L, e.reclaimableByLayer().get(1));
    }

    @Test
    void hardlinkAddsNoContentBytes() {
        OverlayEngine e = new OverlayEngine();
        e.apply(0, "orig", file("orig", 99, 0));
        e.apply(1, "alias", new FsNode("alias", EntryType.HARDLINK, 0, "orig", null, 1));
        assertEquals(2, e.finalFilesystem().size());
        assertEquals(0, e.totalReclaimableBytes());
    }

    @Test
    void resultsAreSortedDeterministically() {
        OverlayEngine e = new OverlayEngine();
        e.apply(0, "z.txt", file("z.txt", 1, 0));
        e.apply(0, "a.txt", file("a.txt", 1, 0));
        e.apply(0, "m.txt", file("m.txt", 1, 0));
        assertEquals(java.util.List.of("a.txt", "m.txt", "z.txt"),
                java.util.List.copyOf(e.finalFilesystem().keySet()));
    }
}
