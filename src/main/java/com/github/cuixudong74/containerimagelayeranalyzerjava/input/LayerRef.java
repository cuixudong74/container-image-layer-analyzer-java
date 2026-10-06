package com.github.cuixudong74.containerimagelayeranalyzerjava.input;

/**
 * 层的引用，具体定位方式由 ImageSource 实现解释。
 *
 * @param id        层标识（通常是 diff id 或文件名）
 * @param sizeBytes 层归档字节数（用于进度估算）
 * @param location  实现相关的定位信息（归档内路径或磁盘路径）
 */
public record LayerRef(String id, long sizeBytes, String location) {
}
