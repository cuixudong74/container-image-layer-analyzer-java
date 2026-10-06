package com.github.cuixudong74.containerimagelayeranalyzerjava.input;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** A local image (archive file or exploded directory) opened for streaming reads. */
public interface ImageSource extends AutoCloseable {

    /** Ordered layer descriptors (bottom layer first). */
    List<LayerHandle> layers();

    /** Stable image identifier (config digest or content-derived). */
    String imageId();

    @Override
    void close() throws IOException;

    /** One layer; opening the stream never buffers the whole layer. */
    interface LayerHandle {
        int index();
        String id();
        /** Uncompressed size in bytes if cheaply known, else -1. */
        long sizeHint();
        InputStream openStream() throws IOException;
    }
}
