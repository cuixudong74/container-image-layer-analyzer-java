package com.github.cuixudong74.containerimagelayeranalyzerjava.input;

import com.github.cuixudong74.containerimagelayeranalyzerjava.tar.TarFormatException;
import com.github.cuixudong74.containerimagelayeranalyzerjava.tar.TarReader;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Image source backed by a local image archive (docker save or OCI layout tar,
 * optionally gzipped). The archive is scanned once to record layer offsets;
 * layer content is then read through independent bounded streams, so memory
 * stays bounded regardless of archive size. A gzipped outer archive is first
 * decompressed (streaming) to a temporary file which is removed on close.
 */
public final class ArchiveImageSource implements ImageSource {

    /** Cap on metadata JSON kept in memory. */
    private static final long META_CAP = 64L << 20;

    private final Path tarFile;
    private final boolean tempOwned;
    private final int bufferSize;
    private final ImageLayout.Layout layout;
    private final Map<String, long[]> entryOffsets; // location -> [dataOffset, size]
    private final List<LayerHandle> layers;
    private final String imageId;

    public ArchiveImageSource(Path archive, int bufferSize) throws IOException {
        this(archive, bufferSize, null);
    }

    /**
     * @param tempDir directory for the decompressed copy of a gzipped archive;
     *                null uses the system default temp directory
     */
    public ArchiveImageSource(Path archive, int bufferSize, Path tempDir) throws IOException {
        this.bufferSize = bufferSize;
        Path plain = archive;
        boolean owned = false;
        if (isGzip(archive)) {
            plain = decompressToTemp(archive, tempDir);
            owned = true;
        }
        this.tarFile = plain;
        this.tempOwned = owned;
        try {
            this.entryOffsets = new HashMap<>();
            byte[] dockerManifest = null;
            byte[] ociIndex = null;
            try (CountingInputStream in = countingStream()) {
                TarReader reader = new TarReader(in);
                com.github.cuixudong74.containerimagelayeranalyzerjava.tar.TarEntryHeader h;
                while ((h = reader.nextEntry()) != null) {
                    String name = h.name();
                    entryOffsets.put(name, new long[]{in.count(), h.size()});
                    if (("manifest.json".equals(name) || "index.json".equals(name)) && h.size() <= META_CAP) {
                        byte[] data = reader.currentEntryStream().readAllBytes();
                        if ("manifest.json".equals(name)) {
                            dockerManifest = data;
                        } else {
                            ociIndex = data;
                        }
                    }
                }
            } catch (TarFormatException e) {
                throw new IOException("malformed image archive: " + e.getMessage(), e);
            }
            if (dockerManifest != null) {
                this.layout = ImageLayout.parseDockerManifest(dockerManifest);
            } else if (ociIndex != null) {
                this.layout = ImageLayout.parseOci(ociIndex, this::readBlobAtOffset);
            } else {
                throw new IOException("archive contains neither manifest.json nor index.json");
            }
            List<LayerHandle> hs = new ArrayList<>();
            int i = 0;
            for (ImageLayout.LayerRef ref : layout.layers()) {
                long[] off = entryOffsets.get(ref.location());
                if (off == null) {
                    throw new IOException("layer listed in manifest but missing in archive: " + ref.location());
                }
                hs.add(new ArchiveLayer(i++, ref.id(), ref.location(), off[0], off[1], ref.gzipped()));
            }
            this.layers = List.copyOf(hs);
            this.imageId = layout.imageId();
        } catch (IOException | RuntimeException e) {
            cleanupTemp();
            throw e;
        }
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
    public void close() throws IOException {
        cleanupTemp();
    }

    // ------------------------------------------------------------------

    private byte[] readBlobAtOffset(String location) throws IOException {
        long[] off = entryOffsets.get(location);
        if (off == null || off[1] > META_CAP) {
            return null;
        }
        try (InputStream in = boundedStream(off[0], off[1])) {
            return in.readAllBytes();
        }
    }

    private CountingInputStream countingStream() throws IOException {
        FileChannel ch = FileChannel.open(tarFile, StandardOpenOption.READ);
        InputStream base = Channels.newInputStream(ch);
        return new CountingInputStream(new BufferedInputStream(base, bufferSize), ch);
    }

    private InputStream boundedStream(long offset, long size) throws IOException {
        FileChannel ch = FileChannel.open(tarFile, StandardOpenOption.READ);
        ch.position(offset);
        InputStream in = Channels.newInputStream(ch);
        return new BoundedInputStream(in, size);
    }

    private void cleanupTemp() throws IOException {
        if (tempOwned) {
            Files.deleteIfExists(tarFile);
        }
    }

    private static boolean isGzip(Path p) throws IOException {
        try (InputStream in = Files.newInputStream(p)) {
            int a = in.read();
            int b = in.read();
            return a == 0x1f && b == 0x8b;
        }
    }

    private Path decompressToTemp(Path archive, Path tempDir) throws IOException {
        Path tmp = tempDir == null
                ? Files.createTempFile("cila-archive-", ".tar")
                : Files.createTempFile(tempDir, "cila-archive-", ".tar");
        boolean ok = false;
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(archive), bufferSize));
             var out = Files.newOutputStream(tmp, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buf = new byte[bufferSize];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            ok = true;
        } finally {
            if (!ok) {
                Files.deleteIfExists(tmp);
            }
        }
        return tmp;
    }

    // ------------------------------------------------------------------

    private final class ArchiveLayer implements LayerHandle {
        private final int index;
        private final String id;
        private final String location;
        private final long offset;
        private final long size;
        private final boolean gzipped;

        ArchiveLayer(int index, String id, String location, long offset, long size, boolean gzipped) {
            this.index = index;
            this.id = id;
            this.location = location;
            this.offset = offset;
            this.size = size;
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
            return size;
        }

        @Override
        public InputStream openStream() throws IOException {
            InputStream in = new BufferedInputStream(boundedStream(offset, size), bufferSize);
            in.mark(2);
            int a = in.read();
            int b = in.read();
            in.reset();
            boolean gz = gzipped || (a == 0x1f && b == 0x8b);
            return gz ? new GZIPInputStream(in, bufferSize) : in;
        }
    }

    /** Counts bytes delivered to the reader (i.e. the exact tar read position). */
    private static final class CountingInputStream extends InputStream {
        private final InputStream delegate;
        private final FileChannel channel;
        private long count;

        CountingInputStream(InputStream delegate, FileChannel channel) {
            this.delegate = delegate;
            this.channel = channel;
        }

        long count() {
            return count;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = delegate.read(buf, off, len);
            if (n > 0) {
                count += n;
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long s = delegate.skip(n);
            count += s;
            return s;
        }

        @Override
        public void close() throws IOException {
            try {
                delegate.close();
            } finally {
                channel.close();
            }
        }
    }

    /** Limits reads to a fixed number of bytes. */
    private static final class BoundedInputStream extends InputStream {
        private final InputStream delegate;
        private long remaining;

        BoundedInputStream(InputStream delegate, long size) {
            this.delegate = delegate;
            this.remaining = size;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = delegate.read();
            if (b >= 0) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int n = delegate.read(buf, off, (int) Math.min(len, remaining));
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
