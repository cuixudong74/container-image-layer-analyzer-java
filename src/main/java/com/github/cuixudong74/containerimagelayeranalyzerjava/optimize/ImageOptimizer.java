package com.github.cuixudong74.containerimagelayeranalyzerjava.optimize;

import com.github.cuixudong74.containerimagelayeranalyzerjava.analyzer.ProgressListener;
import com.github.cuixudong74.containerimagelayeranalyzerjava.analyzer.ScanSession;
import com.github.cuixudong74.containerimagelayeranalyzerjava.config.AnalyzerProperties;
import com.github.cuixudong74.containerimagelayeranalyzerjava.input.ImageSource;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.EntryType;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.ImageReport;
import com.github.cuixudong74.containerimagelayeranalyzerjava.overlay.FsNode;
import com.github.cuixudong74.containerimagelayeranalyzerjava.tar.TarEntryHeader;
import com.github.cuixudong74.containerimagelayeranalyzerjava.tar.TarFormatException;
import com.github.cuixudong74.containerimagelayeranalyzerjava.tar.TarReader;
import com.github.cuixudong74.containerimagelayeranalyzerjava.tar.TarWriter;
import com.github.cuixudong74.containerimagelayeranalyzerjava.task.TaskPlan;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;

/**
 * Produces an optimized image with redundant content removed.
 *
 * <p>Strategy: scan all layers to reconstruct the final filesystem, then emit
 * a single squashed layer containing exactly the entries of that final
 * filesystem. Overwritten and whited-out content disappears by construction,
 * so the optimized image's merged filesystem is equivalent to the original's.
 * Files with identical content (same SHA-256) are emitted once; later
 * duplicates become hardlinks to the first emitted path.
 *
 * <p>The output is staged in a temporary sibling directory and atomically
 * renamed on success; cancellation or failure removes the staging directory,
 * so no partial product is ever left behind.
 */
public final class ImageOptimizer {

    static final String OPTIMIZED_LAYER_DIR = "optimized0";

    private final AnalyzerProperties properties;

    public ImageOptimizer(AnalyzerProperties properties) {
        this.properties = properties;
    }

    /** Synchronous convenience wrapper around {@link #createPlan}. */
    public ImageReport optimize(ImageSource source, Path outputDir) throws IOException {
        TaskPlan plan = createPlan(source, outputDir, noopProgress());
        try {
            boolean more = true;
            while (more) {
                try {
                    more = plan.step();
                } catch (IOException | RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IOException(e);
                }
            }
            return (ImageReport) plan.result();
        } finally {
            try {
                plan.close();
            } catch (Exception e) {
                // best effort
            }
        }
    }

    /**
     * Creates a step-wise optimization plan for the shared task manager.
     * Units: scan of each layer, one finalize-scan unit, write pass per layer,
     * one publish unit.
     */
    public TaskPlan createPlan(ImageSource source, Path outputDir, ProgressListener progress) {
        return new OptimizePlan(source, outputDir, progress);
    }

    private static ProgressListener noopProgress() {
        return new ProgressListener() {
            @Override public void onLayerStart(int layerIndex, String layerId) { }
            @Override public void onBytesScanned(long bytes) { }
            @Override public void onLayerDone(int layerIndex) { }
            @Override public boolean isCancelled() { return false; }
        };
    }

    // ------------------------------------------------------------------

    private final class OptimizePlan implements TaskPlan {
        private final ScanSession session;
        private final Path outputDir;
        private final Path stagingDir;
        private final ProgressListener progress;

        private ImageReport report;
        private Map<Integer, Set<String>> neededByLayer; // layer -> paths to keep
        private Map<String, FsNode> finalFs;
        private int phase; // 0=scanning, 1=writing, 2=publishing, 3=done
        private int writeLayer;
        private boolean published;

        OptimizePlan(ImageSource source, Path outputDir, ProgressListener progress) {
            this.session = new ScanSession(source, properties);
            this.outputDir = outputDir;
            this.stagingDir = outputDir.resolveSibling(outputDir.getFileName() + ".tmp");
            this.progress = progress;
        }

        @Override
        public int totalUnits() {
            return 2 * session.totalLayers() + 2;
        }

        @Override
        public boolean step() throws Exception {
            checkCancelled();
            switch (phase) {
                case 0 -> {
                    session.scanNextLayer(progress);
                    if (!session.hasNextLayer()) {
                        phase = 1;
                    }
                    return true;
                }
                case 1 -> {
                    // finalize scan (one unit)
                    report = session.finish();
                    finalFs = session.overlay().finalFilesystem();
                    neededByLayer = new HashMap<>();
                    for (FsNode n : finalFs.values()) {
                        neededByLayer.computeIfAbsent(n.originLayerIndex(), k -> new HashSet<>()).add(n.path());
                    }
                    Files.createDirectories(stagingDir);
                    phase = 2;
                    return true;
                }
                case 2 -> {
                    if (writeLayer < session.totalLayers()) {
                        writePass(writeLayer);
                        writeLayer++;
                        return true;
                    }
                    phase = 3;
                    return true;
                }
                case 3 -> {
                    publish();
                    phase = 4;
                    return false;
                }
                default -> throw new IllegalStateException("plan already finished");
            }
        }

        @Override
        public Object result() {
            return report;
        }

        @Override
        public void close() throws Exception {
            session.close();
            if (!published) {
                deleteRecursively(stagingDir);
            }
        }

        // ---------------- write phase ----------------

        /** Streams source layer {@code layerIndex} and copies kept entries into the squashed layer tar. */
        private void writePass(int layerIndex) throws IOException {
            Set<String> needed = neededByLayer.getOrDefault(layerIndex, Set.of());
            Path layerTar = stagingDir.resolve(OPTIMIZED_LAYER_DIR).resolve("layer.tar");
            Files.createDirectories(layerTar.getParent());
            byte[] buffer = new byte[properties.getBufferSizeBytes()];
            Map<String, String> emittedContentPath = emittedContentPath();

            try (InputStream raw = session.source().layers().get(layerIndex).openStream();
                 TarReader reader = new TarReader(new BufferedInputStream(raw, properties.getBufferSizeBytes()));
                 OutputStream fileOut = new BufferedOutputStream(
                         Files.newOutputStream(layerTar,
                                 layerIndex == 0 ? java.nio.file.StandardOpenOption.CREATE
                                         : java.nio.file.StandardOpenOption.APPEND),
                         properties.getBufferSizeBytes());
                 TarWriter writer = new TarWriter(fileOut, false)) {
                TarEntryHeader h;
                while ((h = reader.nextEntry()) != null) {
                    checkCancelled();
                    String path = h.name();
                    FsNode node = finalFs.get(path);
                    if (!needed.contains(path) || node == null || node.originLayerIndex() != layerIndex) {
                        reader.skipCurrentEntry();
                        continue;
                    }
                    switch (node.type()) {
                        case FILE -> {
                            String key = node.sha256() + ":" + node.size();
                            String existing = emittedContentPath.get(key);
                            if (existing != null) {
                                writer.putHardlink(path, existing);
                                reader.skipCurrentEntry();
                            } else {
                                writer.putFile(path, node.size());
                                writer.writeContent(reader.currentEntryStream(), node.size(), buffer);
                                emittedContentPath.put(key, path);
                            }
                        }
                        case DIRECTORY -> {
                            writer.putDirectory(path);
                            reader.skipCurrentEntry();
                        }
                        case SYMLINK -> {
                            writer.putSymlink(path, node.linkTarget());
                            reader.skipCurrentEntry();
                        }
                        case HARDLINK -> {
                            writer.putHardlink(path, node.linkTarget());
                            reader.skipCurrentEntry();
                        }
                        default -> reader.skipCurrentEntry();
                    }
                    progress.onBytesScanned(node.size());
                }
            } catch (TarFormatException e) {
                throw new IOException("layer " + layerIndex + " unreadable during write pass: " + e.getMessage(), e);
            }
        }

        /**
         * Lazily builds the dedup map seeded with nothing; populated as content
         * is emitted so hardlinks always point at already-written entries.
         */
        private Map<String, String> emittedContentPath() {
            if (emittedContent == null) {
                emittedContent = new HashMap<>();
            }
            return emittedContent;
        }

        private Map<String, String> emittedContent;

        private void publish() throws IOException {
            // manifest.json + synthetic config, deterministic content
            String configName = "optimized-config.json";
            String config = "{\"architecture\":\"amd64\",\"os\":\"linux\",\"rootfs\":{\"type\":\"layers\","
                    + "\"diff_ids\":[\"sha256:optimized\"]},"
                    + "\"history\":[{\"created\":\"1970-01-01T00:00:00Z\",\"comment\":\"squashed by cila\"}]}";
            Files.writeString(stagingDir.resolve(configName), config, StandardCharsets.UTF_8);
            String manifest = "[{\"Config\":\"" + configName + "\",\"RepoTags\":[\"optimized:latest\"],"
                    + "\"Layers\":[\"" + OPTIMIZED_LAYER_DIR + "/layer.tar\"]}]";
            Files.writeString(stagingDir.resolve("manifest.json"), manifest, StandardCharsets.UTF_8);
            // ensure the layer tar exists and terminates with end-of-archive
            // markers (per-pass writers suppress them so passes can be appended)
            Path layerTar = stagingDir.resolve(OPTIMIZED_LAYER_DIR).resolve("layer.tar");
            Files.createDirectories(layerTar.getParent());
            try (OutputStream out = Files.newOutputStream(layerTar,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) {
                out.write(new byte[1024]);
            }
            if (Files.exists(outputDir)) {
                deleteRecursively(outputDir);
            }
            Files.move(stagingDir, outputDir, StandardCopyOption.ATOMIC_MOVE);
            published = true;
        }

        private void checkCancelled() {
            if (!progress.isCancelled()) {
                return;
            }
            throw new CancellationException("optimize cancelled");
        }
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (var walk = Files.walk(p)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }
}
