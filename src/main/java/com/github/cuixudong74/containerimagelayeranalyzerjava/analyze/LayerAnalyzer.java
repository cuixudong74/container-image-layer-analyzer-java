package com.github.cuixudong74.containerimagelayeranalyzerjava.analyze;

import com.github.cuixudong74.containerimagelayeranalyzerjava.config.AnalyzerProperties;
import com.github.cuixudong74.containerimagelayeranalyzerjava.input.ImageSource;
import com.github.cuixudong74.containerimagelayeranalyzerjava.input.LayerRef;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.EntryType;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.ErrorReason;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.ImageReport;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.LayerEntry;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.LayerError;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.LayerReport;
import com.github.cuixudong74.containerimagelayeranalyzerjava.overlay.OverlayEngine;
import com.github.cuixudong74.containerimagelayeranalyzerjava.tar.TarEntryHeader;
import com.github.cuixudong74.containerimagelayeranalyzerjava.tar.TarParseException;
import com.github.cuixudong74.containerimagelayeranalyzerjava.tar.TarStreamReader;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * 逐层流式扫描并产出整镜像报告。
 *
 * <p>内存上界：单个 {@code ioBufferBytes} 缓冲区 + 与最终文件系统条目数
 * 成正比的元数据，绝不把整层或整个镜像读入内存。
 *
 * <p>确定性：条目按层序处理，统计全部增量累加；报告中的列表在输出前
 * 按稳定规则排序（字节数降序，键字典序升序），与并发度、处理顺序无关。
 */
public class LayerAnalyzer {
    private final AnalyzerProperties properties;

    public LayerAnalyzer(AnalyzerProperties properties) {
        this.properties = properties;
    }

    /**
     * @param source    镜像输入（调用方负责关闭）
     * @param progress  进度回调，可为 null
     * @param cancelled 取消信号（返回 true 时尽快停止并抛 {@link ScanCancelledException}）
     */
    public ImageReport analyze(ImageSource source, ProgressTracker progress,
                               BooleanSupplier cancelled) throws IOException {
        AnalysisSession session = beginAnalysis(source, progress, cancelled);
        while (runAnalysisSlice(session)) {
            // 逐层推进
        }
        return finishAnalysis(session);
    }

    /** 分析会话：以层为粒度的可中断执行状态，供公平调度器分片驱动。 */
    public static final class AnalysisSession {
        private ImageSource source;
        private List<LayerRef> layers;
        private OverlayEngine overlay;
        private ImageReport report;
        private ProgressTracker tracker;
        private BooleanSupplier cancelled;
        private int index;

        public int getTotalLayers() {
            return layers.size();
        }

        public int getNextLayerIndex() {
            return index;
        }

        public ProgressTracker getTracker() {
            return tracker;
        }
    }

    /** 开始分析会话。 */
    public AnalysisSession beginAnalysis(ImageSource source, ProgressTracker progress,
                                         BooleanSupplier cancelled) throws IOException {
        AnalysisSession s = new AnalysisSession();
        s.source = source;
        s.layers = source.listLayers();
        s.overlay = new OverlayEngine();
        s.report = new ImageReport();
        s.report.setImageId(source.imageId());
        s.cancelled = cancelled;
        long totalBytes = 0;
        for (LayerRef l : s.layers) {
            if (l.sizeBytes() > 0) {
                totalBytes += l.sizeBytes();
            }
        }
        s.tracker = progress != null ? progress : new ProgressTracker(totalBytes);
        s.tracker.setTotalLayers(s.layers.size());
        return s;
    }

    /** 扫描下一层；全部完成返回 false。 */
    public boolean runAnalysisSlice(AnalysisSession s) {
        if (s.index >= s.layers.size()) {
            return false;
        }
        checkCancelled(s.cancelled);
        LayerRef layer = s.layers.get(s.index);
        s.tracker.onLayerStart(s.index, layer.id());
        LayerReport layerReport = scanLayer(s.source, layer, s.index, s.overlay,
                s.tracker, s.cancelled);
        s.report.getLayers().add(layerReport);
        s.index++;
        return true;
    }

    /** 汇总产出报告（仅在所有分片完成后调用）。 */
    public ImageReport finishAnalysis(AnalysisSession s) {
        ImageReport report = s.report;
        OverlayEngine overlay = s.overlay;
        report.setTotalBytes(report.getLayers().stream().mapToLong(LayerReport::getTotalBytes).sum());
        report.setFinalFileSystemBytes(overlay.finalFileSystemBytes());
        report.setReclaimableBytes(overlay.reclaimableBytes());
        report.getTopLayers().addAll(
                topLayerContributions(report.getLayers(), overlay, properties.getTopContributors()));
        report.getTopPaths().addAll(
                topPathContributions(overlay, properties.getTopContributors()));
        return report;
    }

    private LayerReport scanLayer(ImageSource source, LayerRef layer, int layerIndex,
                                  OverlayEngine overlay, ProgressTracker tracker,
                                  BooleanSupplier cancelled) {
        LayerReport lr = new LayerReport(layerIndex, layer.id());
        streamLayerEntries(source, layer, layerIndex, lr, tracker, cancelled, entry -> {
            lr.addEntry(entry.type(), entry.size());
            overlay.apply(entry);
        });
        return lr;
    }

    /**
     * 流式扫描单层，把每个合法条目交给 consumer。
     * 供分析器与优化器共用，保证两者看到完全一致的条目流。
     * 层损坏时错误记录到 {@code lr}（状态置 FAILED），不抛出。
     */
    public void streamLayerEntries(ImageSource source, LayerRef layer, int layerIndex,
                                   LayerReport lr, ProgressTracker tracker,
                                   BooleanSupplier cancelled,
                                   java.util.function.Consumer<LayerEntry> consumer) {
        byte[] buffer = new byte[properties.getIoBufferBytes()];
        try (InputStream in = source.openLayer(layer);
             TarStreamReader tar = new TarStreamReader(in, buffer.length)) {
            TarEntryHeader header;
            while ((header = tar.nextEntry()) != null) {
                checkCancelled(cancelled);
                LayerEntry entry = toEntry(header, layerIndex, tar, buffer, tracker, lr);
                if (entry == null) {
                    tar.skipEntry();
                    continue; // 非法元数据条目：记录错误后跳过
                }
                consumer.accept(entry);
                if (tracker != null) {
                    tracker.onEntry();
                    tracker.onBytes(entry.size());
                }
            }
        } catch (TarParseException e) {
            lr.setStatus(LayerReport.Status.FAILED);
            lr.getErrors().add(new LayerError(layerIndex, layer.id(), e.getReason(), e.getMessage()));
        } catch (EOFException e) {
            lr.setStatus(LayerReport.Status.FAILED);
            lr.getErrors().add(new LayerError(layerIndex, layer.id(),
                    ErrorReason.TRUNCATED_ARCHIVE, String.valueOf(e.getMessage())));
        } catch (IOException e) {
            lr.setStatus(LayerReport.Status.FAILED);
            lr.getErrors().add(new LayerError(layerIndex, layer.id(),
                    ErrorReason.IO_ERROR, String.valueOf(e.getMessage())));
        }
    }

    /**
     * 把 tar 头转换为 LayerEntry；普通文件在此流式计算 SHA-256。
     * 返回 null 表示该条目元数据非法，应跳过（错误已记录到 lr）。
     */
    private LayerEntry toEntry(TarEntryHeader header, int layerIndex, TarStreamReader tar,
                               byte[] buffer, ProgressTracker tracker, LayerReport lr)
            throws IOException {
        String path = normalizePath(header.name());
        if (path == null || path.length() > properties.getMaxPathLength()) {
            lr.getErrors().add(new LayerError(layerIndex, null,
                    ErrorReason.INVALID_METADATA, "bad entry path: " + header.name()));
            return null;
        }
        EntryType type = classify(header, path);
        long size = header.typeFlag() == TarEntryHeader.TYPE_REGULAR
                || header.typeFlag() == TarEntryHeader.TYPE_REGULAR_ALT
                ? header.size() : 0;

        String sha = null;
        if (type == EntryType.REGULAR) {
            sha = hashEntry(tar, buffer, header.size(), path);
        } else {
            tar.skipEntry();
        }
        return new LayerEntry(path, type, size, header.linkName(), sha, layerIndex);
    }

    private String hashEntry(TarStreamReader tar, byte[] buffer, long size, String path)
            throws IOException {
        MessageDigest digest = newDigest();
        long remaining = size;
        try (InputStream es = tar.entryStream()) {
            while (remaining > 0) {
                int n = es.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (n < 0) {
                    throw new EOFException("truncated entry content: " + path);
                }
                digest.update(buffer, 0, n);
                remaining -= n;
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static EntryType classify(TarEntryHeader header, String normalizedPath) {
        String base = normalizedPath.substring(normalizedPath.lastIndexOf('/') + 1);
        if (base.equals(".wh..wh..opq")) {
            return EntryType.OPAQUE_MARKER;
        }
        if (base.startsWith(".wh.")) {
            return EntryType.WHITEOUT;
        }
        return switch (header.typeFlag()) {
            case TarEntryHeader.TYPE_REGULAR, TarEntryHeader.TYPE_REGULAR_ALT -> EntryType.REGULAR;
            case TarEntryHeader.TYPE_DIRECTORY -> EntryType.DIRECTORY;
            case TarEntryHeader.TYPE_SYMLINK -> EntryType.SYMLINK;
            case TarEntryHeader.TYPE_HARDLINK -> EntryType.HARDLINK;
            default -> EntryType.OTHER;
        };
    }

    /** 归一化路径；非法（空/穿越残留）返回 null。 */
    public static String normalizePath(String name) {
        String p = name;
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        while (p.endsWith("/") && !p.isEmpty()) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.isEmpty() || p.equals(".")) {
            return null;
        }
        return p;
    }

    /**
     * 主要贡献层：可回收字节降序、层键字典序升序，条数受 topContributors 限制。
     * 排序规则固定，与处理顺序无关。
     */
    public static List<ImageReport.Contribution> topLayerContributions(List<LayerReport> layers,
                                                                       OverlayEngine overlay,
                                                                       int limit) {
        Map<Integer, Long> byLayer = overlay.reclaimableByLayer();
        List<ImageReport.Contribution> out = new ArrayList<>();
        for (Map.Entry<Integer, Long> e : byLayer.entrySet()) {
            if (e.getValue() > 0) {
                String id = e.getKey() < layers.size() ? layers.get(e.getKey()).getLayerId()
                        : String.valueOf(e.getKey());
                out.add(new ImageReport.Contribution(e.getKey() + ":" + id, e.getValue()));
            }
        }
        out.sort(Comparator.comparingLong(ImageReport.Contribution::bytes).reversed()
                .thenComparing(ImageReport.Contribution::key));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /** 主要贡献路径：排序规则与 {@link #topLayerContributions} 相同。 */
    public static List<ImageReport.Contribution> topPathContributions(OverlayEngine overlay,
                                                                      int limit) {
        List<ImageReport.Contribution> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : overlay.reclaimableByPath().entrySet()) {
            if (e.getValue() > 0) {
                out.add(new ImageReport.Contribution(e.getKey(), e.getValue()));
            }
        }
        out.sort(Comparator.comparingLong(ImageReport.Contribution::bytes).reversed()
                .thenComparing(ImageReport.Contribution::key));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw new ScanCancelledException(false);
        }
    }
}
