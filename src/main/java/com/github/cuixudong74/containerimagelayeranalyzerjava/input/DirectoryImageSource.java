package com.github.cuixudong74.containerimagelayeranalyzerjava.input;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Image source backed by an exploded image directory (docker-save layout with
 * {@code manifest.json}, or OCI layout with {@code index.json} + {@code blobs/}).
 * Layer files are streamed directly from disk; nothing is loaded wholesale.
 */
public final class DirectoryImageSource implements ImageSource {

    /** Cap on metadata JSON kept in memory. */
    private static final long META_CAP = 64L << 20;

    private final Path root;
    private final int bufferSize;
    private final List<LayerHandle> layers;
    private final String imageId;

    public DirectoryImageSource(Path root, int bufferSize) throws IOException {
        this.root = root;
        this.bufferSize = bufferSize;
        Path dockerManifest = root.resolve("manifest.json");
        Path ociIndex = root.resolve("index.json");
        ImageLayout.Layout layout;
        if (Files.isRegularFile(dockerManifest)) {
            layout = ImageLayout.parseDockerManifest(readMeta(dockerManifest));
        } else if (Files.isRegularFile(ociIndex)) {
            layout = ImageLayout.parseOci(readMeta(ociIndex), loc -> {
                Path p = root.resolve(loc);
                return Files.isRegularFile(p) ? readMeta(p) : null;
            });
        } else {
            throw new IOException("directory contains neither manifest.json nor index.json: " + root);
        }
        List<LayerHandle> hs = new ArrayList<>();
        int i = 0;
        for (ImageLayout.LayerRef ref : layout.layers()) {
            Path p = root.resolve(ref.location());
            if (!Files.isRegularFile(p)) {
                throw new IOException("layer listed in manifest but missing on disk: " + ref.location());
            }
            hs.add(new DirectoryLayer(i++, ref.id(), p, ref.gzipped()));
        }
        this.layers = List.copyOf(hs);
        this.imageId = layout.imageId();
    }

    @Override
    public List<LayerHandle> layers() {
        return layers;
    }

    @Override
    public String imageId() {
        return imageId;
    }

    @Override
    public void close() {
        // nothing to release
    }

    private static byte[] readMeta(Path p) throws IOException {
        if (Files.size(p) > META_CAP) {
            throw new IOException("metadata file too large: " + p);
        }
        return Files.readAllBytes(p);
    }

    private final class DirectoryLayer implements LayerHandle {
        private final int index;
        private final String id;
        private final Path path;
        private final boolean gzipped;

        DirectoryLayer(int index, String id, Path path, boolean gzipped) {
            this.index = index;
            this.id = id;
            this.path = path;
            this.gzipped = gzipped;
        }

        @Override
        public int index() {
            return index;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public long sizeHint() {
            try {
                return Files.size(path);
            } catch (IOException e) {
                return -1;
            }
        }

        @Override
        public InputStream openStream() throws IOException {
            InputStream in = new BufferedInputStream(Files.newInputStream(path), bufferSize);
            in.mark(2);
            int a = in.read();
            int b = in.read();
            in.reset();
            boolean gz = gzipped || (a == 0x1f && b == 0x8b);
            return gz ? new GZIPInputStream(in, bufferSize) : in;
        }
    }
}
