package com.github.cuixudong74.containerimagelayeranalyzerjava.model;

/**
 * 扫描过程中产出的单个层条目（流式，不缓存文件内容）。
 *
 * @param path       归一化后的相对路径（不以 / 开头，不含 ..）
 * @param type       条目类型
 * @param size       内容字节数（非普通文件为 0）
 * @param linkTarget 符号链接/硬链接目标，其他类型为 null
 * @param sha256     普通文件内容的 SHA-256（小写 hex），其他类型为 null
 * @param layerIndex 所属层序号
 */
public record LayerEntry(String path, EntryType type, long size,
                         String linkTarget, String sha256, int layerIndex) {
}
