package com.github.cuixudong74.containerimagelayeranalyzerjava.optimize;

import java.nio.file.Path;
import java.util.List;

/**
 * 优化结果。
 *
 * @param outputPath       优化产物路径（docker save 格式目录或 tar）
 * @param reclaimableBytes 可回收字节数
 * @param topLayers        主要贡献层（layerId -> bytes）
 * @param topPaths         主要贡献路径（path -> bytes）
 */
public record OptimizeResult(Path outputPath, long reclaimableBytes,
                             List<Contribution> topLayers, List<Contribution> topPaths) {
    public record Contribution(String key, long bytes) {
    }
}
