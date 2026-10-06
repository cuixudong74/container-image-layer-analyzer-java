package com.github.cuixudong74.containerimagelayeranalyzerjava.input;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 根据输入路径形态选择 ImageSource 实现。 */
public final class ImageSourceFactory {
    private ImageSourceFactory() {
    }

    /**
     * @param path 镜像归档文件（tar/tar.gz）或展开后的镜像目录
     */
    public static ImageSource open(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            return new DirectoryImageSource(path);
        }
        if (Files.isRegularFile(path)) {
            return new ArchiveImageSource(path);
        }
        throw new IOException("input path does not exist: " + path);
    }
}
