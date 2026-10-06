package com.github.cuixudong74.containerimagelayeranalyzerjava.tests;

import com.github.cuixudong74.containerimagelayeranalyzerjava.analyze.LayerAnalyzer;
import com.github.cuixudong74.containerimagelayeranalyzerjava.config.AnalyzerProperties;
import com.github.cuixudong74.containerimagelayeranalyzerjava.input.ImageSource;
import com.github.cuixudong74.containerimagelayeranalyzerjava.input.ImageSourceFactory;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.EntryType;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.ErrorReason;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.ImageReport;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.LayerReport;
import com.github.cuixudong74.containerimagelayeranalyzerjava.report.ReportWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.OutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 叠加语义与体积口径的端到端测试。 */
class LayerAnalyzerTest {
    @TempDir
    Path tmp;

    private final AnalyzerProperties props = new AnalyzerProperties();

    private ImageReport analyze(Path image) throws Exception {
        try (ImageSource source = ImageSourceFactory.open(image)) {
            return new LayerAnalyzer(props).analyze(source, null, null);
        }
    }

    @Test
    void crossLayerOverwriteIsReclaimable() throws Exception {
        byte[] v1 = new byte[1000];
        byte[] v2 = new byte[100];
        Path image = TestImages.writeImage(tmp.resolve("img"), List.of(
                TestImages.layer().file("/data", v1),
                TestImages.layer().file("/data", v2)));
        ImageReport r = analyze(image);
        assertEquals(100, r.getFinalFileSystemBytes());
        assertEquals(1000, r.getReclaimableBytes());
        assertEquals(2, r.getLayers().size());
        // 可回收归因到产生浪费的层（第 0 层）
        assertEquals("0:layer0/layer.tar", r.getTopLayers().get(0).key());
        assertEquals(1000, r.getTopLayers().get(0).bytes());
        assertEquals("data", r.getTopPaths().get(0).key());
    }

    @Test
    void whiteoutDeletesLowerEntry() throws Exception {
        Path image = TestImages.writeImage(tmp.resolve("img"), List.of(
                TestImages.layer().file("/keep", "k").file("/gone", "12345"),
                TestImages.layer().whiteout("/gone")));
        ImageReport r = analyze(image);
        assertEquals(1, r.getFinalFileSystemBytes()); // 只剩 /keep 的 1 字节
        assertEquals(5, r.getReclaimableBytes());
        // 删除标记条目被分类统计
        LayerReport l1 = r.getLayers().get(1);
        assertEquals(1L, l1.getEntryCounts().get(EntryType.WHITEOUT));
    }

    @Test
    void opaqueDirHidesLowerChildren() throws Exception {
        Path image = TestImages.writeImage(tmp.resolve("img"), List.of(
                TestImages.layer().dir("/d").file("/d/a", "aa").file("/d/b", "bbb"),
                TestImages.layer().opaque("/d").file("/d/c", "c")));
        ImageReport r = analyze(image);
        assertEquals(1, r.getFinalFileSystemBytes()); // 只有 /d/c
        assertEquals(5, r.getReclaimableBytes()); // a(2)+b(3)
        assertEquals(1L, r.getLayers().get(1).getEntryCounts().get(EntryType.OPAQUE_MARKER));
    }

    @Test
    void hardlinkSharesContentAndSurvivesTargetDeletion() throws Exception {
        Path image = TestImages.writeImage(tmp.resolve("img"), List.of(
                TestImages.layer().file("/a", "hello").hardlink("/b", "/a"),
                TestImages.layer().whiteout("/a")));
        ImageReport r = analyze(image);
        // /a 被删，/b 仍引用同一内容：内容存活且只计一次
        assertEquals(5, r.getFinalFileSystemBytes());
        assertEquals(0, r.getReclaimableBytes());
        assertEquals(1L, r.getLayers().get(0).getEntryCounts().get(EntryType.HARDLINK));
    }

    @Test
    void sameLayerDuplicateContentIsReclaimable() throws Exception {
        Path image = TestImages.writeImage(tmp.resolve("img"), List.of(
                TestImages.layer().file("/x", "same-content").file("/y", "same-content")));
        ImageReport r = analyze(image);
        assertEquals(2 * 12, r.getFinalFileSystemBytes());
        assertEquals(12, r.getReclaimableBytes()); // /y 可改写为硬链接
    }

    @Test
    void entryTypesAreClassified() throws Exception {
        Path image = TestImages.writeImage(tmp.resolve("img"), List.of(
                TestImages.layer()
                        .dir("/d")
                        .file("/d/f", "x")
                        .symlink("/sl", "/d/f")
                        .hardlink("/hl", "/d/f")));
        ImageReport r = analyze(image);
        LayerReport l0 = r.getLayers().get(0);
        assertEquals(1L, l0.getEntryCounts().get(EntryType.DIRECTORY));
        assertEquals(1L, l0.getEntryCounts().get(EntryType.REGULAR));
        assertEquals(1L, l0.getEntryCounts().get(EntryType.SYMLINK));
        assertEquals(1L, l0.getEntryCounts().get(EntryType.HARDLINK));
    }

    @Test
    void truncatedLayerFailsWithReason() throws Exception {
        Path image = TestImages.writeImage(tmp.resolve("img"), List.of(
                TestImages.layer().file("/a", "hello world")));
        Path layerTar = image.resolve("layer0/layer.tar");
        byte[] bytes = Files.readAllBytes(layerTar);
        Files.write(layerTar, java.util.Arrays.copyOf(bytes, bytes.length / 2));
        ImageReport r = analyze(image);
        LayerReport l0 = r.getLayers().get(0);
        assertEquals(LayerReport.Status.FAILED, l0.getStatus());
        assertTrue(l0.getErrors().stream().anyMatch(e ->
                e.reason() == ErrorReason.TRUNCATED_ARCHIVE
                        || e.reason() == ErrorReason.CHECKSUM_MISMATCH));
    }

    @Test
    void pathTraversalEntryIsRejected() throws Exception {
        Path image = TestImages.writeImage(tmp.resolve("img"), List.of(
                TestImages.layer().raw("../evil").file("/ok", "1")));
        ImageReport r = analyze(image);
        LayerReport l0 = r.getLayers().get(0);
        assertEquals(LayerReport.Status.FAILED, l0.getStatus());
        assertTrue(l0.getErrors().stream().anyMatch(e ->
                e.reason() == ErrorReason.PATH_TRAVERSAL));
    }

    @Test
    void corruptChecksumFailsLayer() throws Exception {
        Path image = TestImages.writeImage(tmp.resolve("img"), List.of(
                TestImages.layer().file("/a", "data")));
        Path layerTar = image.resolve("layer0/layer.tar");
        byte[] bytes = Files.readAllBytes(layerTar);
        bytes[10] = (byte) (bytes[10] + 1); // 破坏头内容但不改校验和字段
        Files.write(layerTar, bytes);
        ImageReport r = analyze(image);
        LayerReport l0 = r.getLayers().get(0);
        assertEquals(LayerReport.Status.FAILED, l0.getStatus());
        assertTrue(l0.getErrors().stream().anyMatch(e ->
                e.reason() == ErrorReason.CHECKSUM_MISMATCH));
    }

    @Test
    void reportIsByteIdenticalAcrossRuns() throws Exception {
        Path image = TestImages.writeImage(tmp.resolve("img"), List.of(
                TestImages.layer().dir("/d").file("/d/a", "1").file("/d/b", "22"),
                TestImages.layer().file("/d/a", "333").whiteout("/d/b"),
                TestImages.layer().opaque("/d").file("/d/c", "c")));
        byte[] r1 = ReportWriter.toJsonBytes(analyze(image));
        byte[] r2 = ReportWriter.toJsonBytes(analyze(image));
        assertTrue(java.util.Arrays.equals(r1, r2),
                "相同输入的报告必须逐字节一致");
    }

    @Test
    void archiveInputMatchesDirectoryInput() throws Exception {
        Path dir = TestImages.writeImage(tmp.resolve("img"), List.of(
                TestImages.layer().file("/a", "x"),
                TestImages.layer().file("/a", "yy")));
        // 打成 tar 归档（外层）
        Path archive = tmp.resolve("image.tar");
        try (OutputStream os = Files.newOutputStream(archive);
             var tar = new com.github.cuixudong74.containerimagelayeranalyzerjava.tar.TarStreamWriter(os)) {
            for (String name : new String[]{"manifest.json", "config.json",
                    "layer0/layer.tar", "layer1/layer.tar"}) {
                byte[] content = Files.readAllBytes(dir.resolve(name));
                try (OutputStream es = tar.putFile(name, content.length)) {
                    es.write(content);
                }
            }
            tar.finish();
        }
        byte[] fromDir = ReportWriter.toJsonBytes(analyze(dir));
        byte[] fromArchive = ReportWriter.toJsonBytes(analyze(archive));
        assertTrue(java.util.Arrays.equals(fromDir, fromArchive),
                "归档与目录两种输入的报告必须一致");
    }
}
