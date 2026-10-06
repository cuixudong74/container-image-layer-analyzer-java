package com.github.cuixudong74.containerimagelayeranalyzerjava.input;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Picks the right ImageSource for a user-supplied path. */
public final class ImageSourceFactory {

    private ImageSourceFactory() {
    }

    public static ImageSource open(Path path, int bufferSize) throws IOException {
        return open(path, bufferSize, null);
    }

    public static ImageSource open(Path path, int bufferSize, Path tempDir) throws IOException {
        if (Files.isDirectory(path)) {
            return new DirectoryImageSource(path, bufferSize);
        }
        if (Files.isRegularFile(path)) {
            return new ArchiveImageSource(path, bufferSize, tempDir);
        }
        throw new IOException("image path is neither a regular file nor a directory: " + path);
    }
}
