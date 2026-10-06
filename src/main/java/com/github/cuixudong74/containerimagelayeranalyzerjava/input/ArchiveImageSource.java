package com.github.cuixudong74.containerimagelayeranalyzerjava.input;

import com.github.cuixudong74.containerimagelayeranalyzerjava.tar.TarEntryHeader;
import com.github.cuixudong74.containerimagelayeranalyzerjava.tar.TarParseException;
import com.github.cuixudong74.containerimagelayeranalyzerjava.tar.TarStreamReader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * 本地镜像归档（docker save / OCI layout 的 tar 或 tar.gz）输入。
 *
 * <p>实现方式：以固定缓冲流式解包到临时目录（内存占用有上界），
 * 再委托 {@link DirectoryImageSource}。临时目录在 {@link #close()} 时删除；
 * 解包失败同样清理，不残留中间产物。
 */
public class ArchiveImageSource implements ImageSource {
    private static final int COPY_BUFFER = 64 * 1024;

    private final Path tempDir;
    private final DirectoryImageSource delegate;

    public ArchiveImageSource(Path archive) throws IOException {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("image-archive-");
            extract(archive, dir);
            this.tempDir = dir;
            this.delegate = new DirectoryImageSource(dir);
        } catch (IOException | RuntimeException e) {
            if (dir != null) {
                deleteRecursively(dir);
            }
            throw e;
        }
    }

    @Override
    public String imageId() throws IOException {
        return delegate.imageId();
    }

    @Override
    public List<LayerRef> listLayers() throws IOException {
        return delegate.listLayers();
    }

    @Override
    public InputStream openLayer(LayerRef layer) throws IOException {
        return delegate.openLayer(layer);
    }

    @Override
    public void close() throws IOException {
        if (tempDir != null) {
            deleteRecursively(tempDir);
        }
    }

    private static void extract(Path archive, Path targetDir) throws IOException {
        try (InputStream raw = Files.newInputStream(archive);
             InputStream in = maybeGunzip(raw);
             TarStreamReader tar = new TarStreamReader(in, COPY_BUFFER)) {
            byte[] buf = new byte[COPY_BUFFER];
            TarEntryHeader h;
            while ((h = tar.nextEntry()) != null) {
                String name = h.name();
                Path out = targetDir.resolve(name).normalize();
                if (!out.startsWith(targetDir)) {
                    throw new IOException("unsafe entry in archive: " + name);
                }
                switch (h.typeFlag()) {
                    case TarEntryHeader.TYPE_DIRECTORY -> {
                        Files.createDirectories(out);
                        tar.skipEntry();
                    }
                    case TarEntryHeader.TYPE_REGULAR, TarEntryHeader.TYPE_REGULAR_ALT -> {
                        if (out.getParent() != null) {
                            Files.createDirectories(out.getParent());
                        }
                        try (InputStream es = tar.entryStream();
                             OutputStream os = Files.newOutputStream(out)) {
                            es.transferTo(os);
                        }
                        tar.skipEntry();
                    }
                    default -> tar.skipEntry(); // 链接等其他条目在归档外层忽略
                }
            }
        } catch (TarParseException e) {
            throw new IOException("corrupt image archive: " + e.getMessage(), e);
        }
    }

    private static InputStream maybeGunzip(InputStream raw) throws IOException {
        PushbackInputStream pb = new PushbackInputStream(raw, 2);
        byte[] magic = new byte[2];
        int n = pb.read(magic);
        if (n > 0) {
            pb.unread(magic, 0, n);
        }
        if (n == 2 && (magic[0] & 0xff) == 0x1f && (magic[1] & 0xff) == 0x8b) {
            return new GZIPInputStream(pb);
        }
        return pb;
    }

    static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }
}
