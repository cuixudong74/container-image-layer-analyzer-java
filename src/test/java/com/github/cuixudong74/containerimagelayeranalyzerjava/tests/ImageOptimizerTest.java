package com.github.cuixudong74.containerimagelayeranalyzerjava.tests;

import com.github.cuixudong74.containerimagelayeranalyzerjava.analyze.LayerAnalyzer;
import com.github.cuixudong74.containerimagelayeranalyzerjava.config.AnalyzerProperties;
import com.github.cuixudong74.containerimagelayeranalyzerjava.input.ImageSource;
import com.github.cuixudong74.containerimagelayeranalyzerjava.input.ImageSourceFactory;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.ImageReport;
import com.github.cuixudong74.containerimagelayeranalyzerjava.optimize.ImageOptimizer;
import com.github.cuixudong74.containerimagelayeranalyzerjava.optimize.OptimizeResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 优化产物的最终文件系统等价性与可回收口径测试。 */
class ImageOptimizerTest {
    @TempDir
    Path tmp;

    private final AnalyzerProperties props = new AnalyzerProperties();

    private ImageReport analyze(Path image) throws Exception {
        try (ImageSource source = ImageSourceFactory.open(image)) {
            return new LayerAnalyzer(props).analyze(source, null, null);
        }
    }

    private OptimizeResult optimize(Path image, Path out) throws Exception {
        try (ImageSource source = ImageSourceFactory.open(image)) {
            return new ImageOptimizer(props).optimize(source, out, null);
        }
    }

    @Test
    void optimizedImageHasEquivalentFinalFileSystem() throws Exception {
        Path image = TestImages.writeImage(tmp.resolve("img"), List.of(
                TestImages.layer().dir("/d").file("/d/a", "aaaa").file("/d/b", "bb")
                        .file("/big", new byte[5000]),
                TestImages.layer().file("/big", new byte[100]).whiteout("/d/b"),
                TestImages.layer().opaque("/d").file("/d/c", "c")));
        ImageReport before = analyze(image);
        Path out = tmp.resolve("optimized");
        OptimizeResult result = optimize(image, out);

        ImageReport after = analyze(out);
        // 最终文件系统等价
        assertEquals(before.getFinalFileSystemBytes(), after.getFinalFileSystemBytes());
        // 优化产物自身不再有可回收空间（冗余已清理）
        assertEquals(0, after.getReclaimableBytes());
        // 报告给出可回收字节数与主要贡献者
        assertEquals(before.getReclaimableBytes(), result.reclaimableBytes());
        assertTrue(result.reclaimableBytes() > 0);
        assertFalse(result.topLayers().isEmpty());
        assertFalse(result.topPaths().isEmpty());
    }

    @Test
    void hardlinkMaterializationPreservesContent() throws Exception {
        // /b 硬链接到 /a；/a 后被删除：优化器必须把 /b 物化为普通文件
        Path image = TestImages.writeImage(tmp.resolve("img"), List.of(
                TestImages.layer().file("/a", "hello").hardlink("/b", "/a"),
                TestImages.layer().whiteout("/a")));
        ImageReport before = analyze(image);
        Path out = tmp.resolve("optimized");
        optimize(image, out);
        ImageReport after = analyze(out);
        assertEquals(before.getFinalFileSystemBytes(), after.getFinalFileSystemBytes());
        assertEquals(5, after.getFinalFileSystemBytes());
    }

    @Test
    void sameLayerDuplicatesBecomeHardlinks() throws Exception {
        byte[] content = new byte[2048];
        Path image = TestImages.writeImage(tmp.resolve("img"), List.of(
                TestImages.layer().file("/x", content).file("/y", content)));
        Path out = tmp.resolve("optimized");
        OptimizeResult result = optimize(image, out);
        assertEquals(2048, result.reclaimableBytes());
        ImageReport after = analyze(out);
        assertEquals(0, after.getReclaimableBytes());
        // /y 变为硬链接
        assertEquals(1L, after.getLayers().get(0).getEntryCounts()
                .get(com.github.cuixudong74.containerimagelayeranalyzerjava.model.EntryType.HARDLINK));
    }

    @Test
    void noPartialOutputOnCorruptInput() throws Exception {
        Path image = TestImages.writeImage(tmp.resolve("img"), List.of(
                TestImages.layer().file("/a", "x"),
                TestImages.layer().file("/b", "y")));
        // 破坏第二层
        Path layer1 = image.resolve("layer1/layer.tar");
        byte[] bytes = Files.readAllBytes(layer1);
        Files.write(layer1, java.util.Arrays.copyOf(bytes, 100));
        Path out = tmp.resolve("optimized");
        try {
            optimize(image, out);
            org.junit.jupiter.api.Assertions.fail("corrupt input must not optimize");
        } catch (Exception expected) {
            // 预期失败
        }
        assertFalse(Files.exists(out), "失败不得留下产物");
        // 临时目录也不得残留
        try (Stream<Path> s = Files.list(tmp)) {
            assertTrue(s.noneMatch(p -> p.getFileName().toString().startsWith(".optimize-")),
                    "失败不得残留临时目录");
        }
    }

    @Test
    void existingOutputIsRejected() throws Exception {
        Path image = TestImages.writeImage(tmp.resolve("img"), List.of(
                TestImages.layer().file("/a", "x")));
        Path out = tmp.resolve("optimized");
        Files.createDirectories(out);
        try {
            optimize(image, out);
            org.junit.jupiter.api.Assertions.fail("existing output must be rejected");
        } catch (Exception expected) {
            // 预期失败
        }
    }
}
