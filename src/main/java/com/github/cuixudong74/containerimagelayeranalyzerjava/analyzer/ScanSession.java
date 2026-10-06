package com.github.cuixudong74.containerimagelayeranalyzerjava.analyzer;

import com.github.cuixudong74.containerimagelayeranalyzerjava.config.AnalyzerProperties;
import com.github.cuixudong74.containerimagelayeranalyzerjava.input.ImageSource;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.EntryType;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.ImageReport;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.LayerEntry;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.LayerIssue;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.LayerReport;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.IssueReason;
import com.github.cuixudong74.containerimagelayeranalyzerjava.overlay.FsNode;
import com.github.cuixudong74.containerimagelayeranalyzerjava.overlay.OverlayEngine;
import com.github.cuixudong74.containerimagelayeranalyzerjava.tar.TarEntryHeader;
import com.github.cuixudong74.containerimagelayeranalyzerjava.tar.TarFormatException;
import com.github.cuixudong74.containerimagelayeranalyzerjava.tar.TarReader;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;

/**
 * Stateful, layer-at-a-time scan over an {@link ImageSource}. Splitting the
 * scan into per-layer steps lets the task manager interleave work units of
 * several tasks on a shared executor, so one huge image cannot starve others.
 *
 * <p>Not thread-safe: steps of one session must run sequentially (overlay
 * semantics are order-dependent), but different sessions run concurrently.
 */
public final class ScanSession implements AutoCloseable {

    private final ImageSource source;
    private final AnalyzerProperties properties;
    private final OverlayEngine overlay = new OverlayEngine();
    private final List<LayerReport> layerReports = new ArrayList<>();
    private final List<LayerIssue> issues = new ArrayList<>();
    private int nextLayer;
    private boolean finished;

    public ScanSession(ImageSource source, AnalyzerProperties properties) {
        this.source = source;
        this.properties = properties;
    }

    public int totalLayers() {
        return source.layers().size();
    }

    public boolean hasNextLayer() {
        return nextLayer < source.layers().size();
    }

    public ImageSource source() {
        return source;
    }

    public OverlayEngine overlay() {
        return overlay;
    }

    /** Scans exactly one layer. Progress callbacks fire during the scan. */
    public void scanNextLayer(ProgressListener progress) {
        if (finished) {
            throw new IllegalStateException("session already finished");
        }
        ImageSource.LayerHandle layer = source.layers().get(nextLayer);
        progress.onLayerStart(layer.index(), layer.id());
        LayerReport lr = scanLayer(layer, progress);
        layerReports.add(lr);
        nextLayer++;
        progress.onLayerDone(layer.index());
    }

    /** Builds the final, deterministic report. Callable once all layers are done. */
    public ImageReport finish() {
        if (hasNextLayer()) {
            throw new IllegalStateException("layers remaining: " + (source.layers().size() - nextLayer));
        }
        finished = true;

        Map<EntryType, Long> totalCount = new EnumMap<>(EntryType.class);
        Map<EntryType, Long> totalBytes = new EnumMap<>(EntryType.class);
        long imageBytes = 0;
        for (LayerReport lr : layerReports) {
            imageBytes += lr.totalBytes();
            lr.countByType().forEach((k, v) -> totalCount.merge(k, v, Long::sum));
            lr.bytesByType().forEach((k, v) -> totalBytes.merge(k, v, Long::sum));
        }

        Map<Integer, Long> reclaimByLayer = overlay.reclaimableByLayer();
        Map<String, FsNode> finalFs = overlay.finalFilesystem();
        List<LayerReport> finalized = new ArrayList<>(layerReports.size());
        for (LayerReport lr : layerReports) {
            List<LayerEntry> entries = new ArrayList<>(lr.entries().size());
            for (LayerEntry e : lr.entries()) {
                FsNode cur = finalFs.get(e.path());
                boolean redundant = cur == null || cur.originLayerIndex() != lr.layerIndex();
                entries.add(new LayerEntry(e.path(), e.type(), e.size(), e.linkTarget(), e.sha256(), redundant));
            }
            finalized.add(new LayerReport(lr.layerIndex(), lr.layerId(), lr.totalBytes(),
                    lr.countByType(), lr.bytesByType(),
                    reclaimByLayer.getOrDefault(lr.layerIndex(), 0L),
                    entries, lr.issues()));
        }

        long finalFsBytes = 0;
        Map<String, String> contentSeen = new TreeMap<>();
        long duplicateContentBytes = 0;
        for (FsNode n : finalFs.values()) {
            if (n.type() == EntryType.FILE) {
                finalFsBytes += n.size();
                if (n.sha256() != null) {
                    String key = n.sha256() + ":" + n.size();
                    if (contentSeen.putIfAbsent(key, n.path()) != null) {
                        duplicateContentBytes += n.size();
                    }
                }
            }
        }

        return new ImageReport(
                source.imageId(),
                List.copyOf(finalized),
                imageBytes,
                new EnumMap<>(totalCount),
                new EnumMap<>(totalBytes),
                finalFsBytes,
                overlay.totalReclaimableBytes(),
                duplicateContentBytes,
                topPaths(overlay.reclaimableByPath()),
                topLayers(reclaimByLayer, layerReports),
                List.copyOf(issues));
    }

    @Override
    public void close() throws IOException {
        source.close();
    }

    // ------------------------------------------------------------------

    private LayerReport scanLayer(ImageSource.LayerHandle layer, ProgressListener progress) {
        Map<EntryType, Long> countByType = new EnumMap<>(EntryType.class);
        Map<EntryType, Long> bytesByType = new EnumMap<>(EntryType.class);
        PriorityQueue<LayerEntry> top = new PriorityQueue<>(
                (a, b) -> a.size() != b.size() ? Long.compare(a.size(), b.size())
                        : b.path().compareTo(a.path()));
        long layerBytes = 0;
        int maxEntries = properties.getMaxReportEntriesPerLayer();

        try (InputStream raw = layer.openStream();
             TarReader reader = new TarReader(new BufferedInputStream(raw, properties.getBufferSizeBytes()))) {
            TarEntryHeader h;
            while ((h = reader.nextEntry()) != null) {
                checkCancelled(progress);
                String path = h.name();
                String base = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;

                if (base.equals(ImageAnalyzer.OPAQUE_MARKER)) {
                    overlay.applyOpaqueDir(layer.index(), ImageAnalyzer.parentOf(path));
                    bump(countByType, bytesByType, EntryType.OPAQUE_MARKER, 0);
                    continue;
                }
                if (base.startsWith(ImageAnalyzer.WHITEOUT_PREFIX)) {
                    String parent = ImageAnalyzer.parentOf(path);
                    String target = (parent.isEmpty() ? "" : parent + "/")
                            + base.substring(ImageAnalyzer.WHITEOUT_PREFIX.length());
                    overlay.applyWhiteout(layer.index(), target);
                    bump(countByType, bytesByType, EntryType.WHITEOUT, 0);
                    continue;
                }

                EntryType type = ImageAnalyzer.typeOf(h.typeFlag());
                long size = type == EntryType.FILE ? h.size() : 0;
                String sha = null;
                if (type == EntryType.FILE) {
                    sha = hashCurrentEntry(reader, progress);
                } else {
                    reader.skipCurrentEntry();
                }
                FsNode node = new FsNode(path, type, size,
                        h.linkName().isEmpty() ? null : h.linkName(), sha, layer.index());
                overlay.apply(layer.index(), path, node);
                bump(countByType, bytesByType, type, size);
                layerBytes += size;

                LayerEntry entry = new LayerEntry(path, type, size,
                        h.linkName().isEmpty() ? null : h.linkName(), sha, false);
                if (top.size() < maxEntries) {
                    top.add(entry);
                } else if (compareForTop(entry, top.peek()) > 0) {
                    top.poll();
                    top.add(entry);
                }
            }
        } catch (TarFormatException e) {
            throw new LayerScanException(
                    new LayerIssue(layer.index(), layer.id(), e.getReason(), e.getEntryPath(), e.getMessage()), e);
        } catch (IOException e) {
            throw new LayerScanException(
                    new LayerIssue(layer.index(), layer.id(), IssueReason.IO_ERROR, null, e.getMessage()), e);
        }

        List<LayerEntry> entries = new ArrayList<>(top);
        entries.sort((a, b) -> a.size() != b.size() ? Long.compare(b.size(), a.size())
                : a.path().compareTo(b.path()));
        return new LayerReport(layer.index(), layer.id(), layerBytes,
                new EnumMap<>(countByType), new EnumMap<>(bytesByType), 0, List.copyOf(entries), List.of());
    }

    private String hashCurrentEntry(TarReader reader, ProgressListener progress) throws IOException {
        MessageDigest md = newSha256();
        byte[] buf = new byte[properties.getBufferSizeBytes()];
        InputStream in = reader.currentEntryStream();
        int n;
        while ((n = in.read(buf)) >= 0) {
            md.update(buf, 0, n);
            progress.onBytesScanned(n);
            checkCancelled(progress);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    // ------------------------------------------------------------------

    private static void bump(Map<EntryType, Long> count, Map<EntryType, Long> bytes, EntryType t, long size) {
        count.merge(t, 1L, Long::sum);
        bytes.merge(t, size, Long::sum);
    }

    private static int compareForTop(LayerEntry a, LayerEntry b) {
        int c = Long.compare(a.size(), b.size());
        return c != 0 ? c : -a.path().compareTo(b.path());
    }

    private static List<ImageReport.PathBytes> topPaths(Map<String, Long> byPath) {
        return byPath.entrySet().stream()
                .sorted((a, b) -> !a.getValue().equals(b.getValue())
                        ? Long.compare(b.getValue(), a.getValue())
                        : a.getKey().compareTo(b.getKey()))
                .limit(ImageAnalyzer.TOP_N)
                .map(e -> new ImageReport.PathBytes(e.getKey(), e.getValue()))
                .toList();
    }

    private static List<ImageReport.LayerBytes> topLayers(Map<Integer, Long> byLayer, List<LayerReport> layers) {
        return byLayer.entrySet().stream()
                .sorted((a, b) -> !a.getValue().equals(b.getValue())
                        ? Long.compare(b.getValue(), a.getValue())
                        : Integer.compare(a.getKey(), b.getKey()))
                .limit(ImageAnalyzer.TOP_N)
                .map(e -> new ImageReport.LayerBytes(e.getKey(), layerIdOf(layers, e.getKey()), e.getValue()))
                .toList();
    }

    private static String layerIdOf(List<LayerReport> layers, int index) {
        for (LayerReport lr : layers) {
            if (lr.layerIndex() == index) {
                return lr.layerId();
            }
        }
        return "";
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void checkCancelled(ProgressListener progress) {
        if (progress.isCancelled()) {
            throw new CancellationException("scan cancelled");
        }
    }
}
