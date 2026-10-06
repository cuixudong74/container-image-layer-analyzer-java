package com.github.cuixudong74.containerimagelayeranalyzerjava.optimize;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.cuixudong74.containerimagelayeranalyzerjava.analyze.LayerAnalyzer;
import com.github.cuixudong74.containerimagelayeranalyzerjava.analyze.ScanCancelledException;
import com.github.cuixudong74.containerimagelayeranalyzerjava.config.AnalyzerProperties;
import com.github.cuixudong74.containerimagelayeranalyzerjava.input.ImageSource;
import com.github.cuixudong74.containerimagelayeranalyzerjava.input.LayerRef;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.EntryType;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.ImageReport;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.LayerEntry;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.LayerReport;
import com.github.cuixudong74.containerimagelayeranalyzerjava.overlay.OverlayEngine;
import com.github.cuixudong74.containerimagelayeranalyzerjava.tar.TarEntryHeader;
import com.github.cuixudong74.containerimagelayeranalyzerjava.tar.TarParseException;
import com.github.cuixudong74.containerimagelayeranalyzerjava.tar.TarStreamReader;
import com.github.cuixudong74.containerimagelayeranalyzerjava.tar.TarStreamWriter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

/**
 * 生成清理冗余后的优化镜像（docker save 布局的目录）。
 *
 * <p>等价性：只保留"存活"条目——最终文件系统的胜出条目，以及内容仍被
 * （硬链接）引用的原始条目；删除标记与不透明标记的效果已物化，不再输出。
 * 同层重复内容改写为硬链接。因此优化产物叠加后的最终文件系统与原镜像一致。
 *
 * <p>两阶段流式处理，内存有上界；需要物化的悬空硬链接内容落盘为临时
 * spill 文件。产物先写入临时目录，完成后原子落位；失败/取消清理临时目录，
 * 不残留中间产物。
 *
 * <p>同时提供一次性 {@link #optimize} 与分片会话 API
 * （{@link #beginOptimize}/{@link #runOptimizeSlice}），后者供公平调度器
 * 以层为粒度驱动。
 */
public class ImageOptimizer {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AnalyzerProperties properties;
    private final LayerAnalyzer analyzer;

    public ImageOptimizer(AnalyzerProperties properties) {
        this.properties = properties;
        this.analyzer = new LayerAnalyzer(properties);
    }

    /** 一次性执行优化（内部分片驱动）。 */
    public OptimizeResult optimize(ImageSource source, Path output,
                                   BooleanSupplier cancelled) throws IOException {
        OptimizeSession session = beginOptimize(source, output, cancelled);
        try {
            while (runOptimizeSlice(session)) {
                // 逐层推进
            }
            return finishOptimize(session);
        } catch (IOException | RuntimeException e) {
            abortOptimize(session);
            throw e;
        }
    }

    /** 优化会话：分片执行状态。 */
    public static final class OptimizeSession {
        ImageSource source;
        List<LayerRef> layers;
        Path output;
        BooleanSupplier cancelled;
        Plan plan;
        final List<RegularRec> regulars = new ArrayList<>();
        final List<LayerEntry> markers = new ArrayList<>();
        int planIndex;
        boolean planCompleted;
        int writeIndex;
        Path tmp;
        final Map<String, Path> spillBySha = new HashMap<>();
        OptimizeResult result;
    }

    /** 开始优化会话：校验输出路径、建立临时目录。 */
    public OptimizeSession beginOptimize(ImageSource source, Path output,
                                         BooleanSupplier cancelled) throws IOException {
        if (Files.exists(output)) {
            throw new IOException("output path already exists: " + output);
        }
        OptimizeSession s = new OptimizeSession();
        s.source = source;
        s.layers = source.listLayers();
        s.output = output;
        s.cancelled = cancelled;
        s.plan = new Plan();
        s.plan.overlay = new OverlayEngine();
        s.plan.layerReports = new ArrayList<>();
        return s;
    }

    /**
     * 执行一个分片（分析一层 / 完成计划 / 重写一层 / 落位）；
     * 全部完成返回 false。抛出异常时会话应通过 {@link #abortOptimize} 清理。
     */
    public boolean runOptimizeSlice(OptimizeSession s) throws IOException {
        if (s.planIndex < s.layers.size()) {
            checkCancelled(s.cancelled);
            planOneLayer(s);
            return true;
        }
        if (!s.planCompleted) {
            completePlan(s);
            s.planCompleted = true;
            return true;
        }
        if (s.writeIndex < s.layers.size()) {
            checkCancelled(s.cancelled);
            ensureTmp(s);
            writeOneLayer(s);
            return true;
        }
        if (s.result == null) {
            finalizeOptimize(s);
        }
        return false;
    }

    /** 取结果（仅在 {@link #runOptimizeSlice} 返回 false 后有效）。 */
    public OptimizeResult finishOptimize(OptimizeSession s) {
        return s.result;
    }

    /** 中止会话：清理临时目录与 spill 文件，不残留中间产物。 */
    public void abortOptimize(OptimizeSession s) {
        if (s.tmp != null) {
            try {
                deleteRecursively(s.tmp);
            } catch (IOException ignored) {
                // 清理失败不掩盖原异常
            }
            s.tmp = null;
        }
    }

    // ---- 阶段 1：活性分析（分片） ----

    private record RegularRec(int layer, String path, String sha, long size) {
    }

    private static final class Plan {
        OverlayEngine overlay;
        List<LayerReport> layerReports;
        Map<Integer, Map<String, LayerEntry>> keptOrigins = new HashMap<>();
        Map<Integer, Map<String, LayerEntry>> keptFinal = new HashMap<>();
        Map<Integer, Map<String, String>> dupHardlinks = new HashMap<>();
        Map<Integer, Map<String, String>> materialize = new HashMap<>();
        Set<String> neededShas = new HashSet<>();
        Map<Integer, TreeSet<String>> synthDirs = new HashMap<>();
        /** 层 -> 需保留的删除/不透明标记路径（其效果涉及被保留的原始条目）。 */
        Map<Integer, Set<String>> keptMarkers = new HashMap<>();
        List<String> layerPaths = new ArrayList<>();
        List<String> diffIds = new ArrayList<>();
    }

    private void planOneLayer(OptimizeSession s) throws IOException {
        int index = s.planIndex;
        LayerRef layer = s.layers.get(index);
        LayerReport lr = new LayerReport(index, layer.id());
        analyzer.streamLayerEntries(s.source, layer, index, lr, null, s.cancelled, e -> {
            s.plan.overlay.apply(e);
            if (e.type() == EntryType.REGULAR) {
                s.regulars.add(new RegularRec(index, e.path(), e.sha256(), e.size()));
            }
            if (e.type() == EntryType.WHITEOUT || e.type() == EntryType.OPAQUE_MARKER) {
                s.markers.add(e);
            }
        });
        s.plan.layerReports.add(lr);
        if (lr.getStatus() == LayerReport.Status.FAILED) {
            throw new IOException("cannot optimize: layer " + layer.id() + " is corrupt: "
                    + lr.getErrors());
        }
        s.planIndex++;
    }

    private void completePlan(OptimizeSession s) throws IOException {
        Plan plan = s.plan;
        int layerCount = s.layers.size();

        Map<Long, LayerEntry> origins = plan.overlay.liveInodeOrigins();
        for (LayerEntry origin : origins.values()) {
            plan.keptOrigins.computeIfAbsent(origin.layerIndex(), k -> new HashMap<>())
                    .put(origin.path(), origin);
        }

        Map<String, OverlayEngine.FinalNode> fs = new HashMap<>();
        for (Map.Entry<String, OverlayEngine.FinalNode> e : plan.overlay.finalFileSystem()) {
            fs.put(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, OverlayEngine.FinalNode> e : fs.entrySet()) {
            LayerEntry entry = e.getValue().entry();
            switch (entry.type()) {
                case REGULAR -> {
                    // 存活 inode 的原始条目已收录
                }
                case HARDLINK -> {
                    OverlayEngine.FinalNode target = fs.get(normalize(entry.linkTarget()));
                    if (target != null && target.inodeId() == e.getValue().inodeId()) {
                        plan.keptFinal.computeIfAbsent(entry.layerIndex(), k -> new HashMap<>())
                                .put(entry.path(), entry);
                    } else {
                        LayerEntry origin = origins.get(e.getValue().inodeId());
                        if (origin == null || origin.sha256() == null) {
                            throw new IOException("cannot optimize: dangling hardlink "
                                    + entry.path() + " -> " + entry.linkTarget());
                        }
                        plan.materialize.computeIfAbsent(entry.layerIndex(), k -> new HashMap<>())
                                .put(entry.path(), origin.sha256());
                        plan.neededShas.add(origin.sha256());
                    }
                }
                default -> plan.keptFinal.computeIfAbsent(entry.layerIndex(), k -> new HashMap<>())
                        .put(entry.path(), entry);
            }
        }

        // 同层重复内容：流序首个存活条目为规范，其余改写硬链接
        Map<String, String> canonical = new HashMap<>();
        for (RegularRec r : s.regulars) {
            Map<String, LayerEntry> layerOrigins = plan.keptOrigins.get(r.layer());
            if (layerOrigins == null || !layerOrigins.containsKey(r.path())
                    || r.sha() == null || r.size() == 0) {
                continue;
            }
            String key = r.layer() + " " + r.sha();
            String canon = canonical.get(key);
            if (canon == null) {
                canonical.put(key, r.path());
            } else {
                layerOrigins.remove(r.path());
                plan.dupHardlinks.computeIfAbsent(r.layer(), k -> new TreeMap<>())
                        .put(r.path(), canon);
            }
        }

        // 保留仍有效的删除/不透明标记：目标下存在被保留的原始条目时，
        // 标记必须保留，否则优化产物中删除效果丢失
        for (LayerEntry marker : s.markers) {
            String target = markerTarget(marker);
            boolean opaque = marker.type() == EntryType.OPAQUE_MARKER;
            boolean needed = plan.keptOrigins.values().stream()
                    .flatMap(m -> m.values().stream())
                    .anyMatch(o -> o.layerIndex() < marker.layerIndex()
                            && (opaque
                            ? target.isEmpty() || o.path().startsWith(target + "/")
                            : o.path().equals(target) || o.path().startsWith(target + "/")));
            if (needed) {
                plan.keptMarkers.computeIfAbsent(marker.layerIndex(), k -> new HashSet<>())
                        .add(marker.path());
            }
        }

        // 补齐父目录
        Set<String> knownDirs = new HashSet<>();
        for (int l = 0; l < layerCount; l++) {
            // 本层保留的标记会使相应目录失效，后续层需要重新补写
            Set<String> markersHere = plan.keptMarkers.get(l);
            if (markersHere != null) {
                for (String m : markersHere) {
                    String target = m.endsWith("/.wh..wh..opq")
                            ? m.substring(0, m.length() - "/.wh..wh..opq".length())
                            : whiteoutTarget(m);
                    knownDirs.removeIf(d -> d.equals(target) || d.startsWith(target + "/"));
                }
            }
            TreeSet<String> synth = new TreeSet<>();
            TreeSet<String> emitted = new TreeSet<>();
            addKeys(emitted, plan.keptOrigins.get(l));
            addKeys(emitted, plan.keptFinal.get(l));
            addKeys(emitted, plan.dupHardlinks.get(l));
            addKeys(emitted, plan.materialize.get(l));
            for (String p : emitted) {
                String prefix = p;
                int slash;
                while ((slash = prefix.lastIndexOf('/')) > 0) {
                    prefix = prefix.substring(0, slash);
                    if (!knownDirs.contains(prefix)) {
                        synth.add(prefix);
                    }
                }
            }
            Map<String, LayerEntry> finals = plan.keptFinal.get(l);
            if (finals != null) {
                for (LayerEntry e : finals.values()) {
                    if (e.type() == EntryType.DIRECTORY) {
                        knownDirs.add(e.path());
                    }
                }
            }
            knownDirs.addAll(synth);
            plan.synthDirs.put(l, synth);
        }
    }

    // ---- 阶段 2：流式重写（分片） ----

    private void ensureTmp(OptimizeSession s) throws IOException {
        if (s.tmp == null) {
            Path parent = s.output.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            s.tmp = Files.createTempDirectory(parent != null ? parent : Path.of("."),
                    ".optimize-");
        }
    }

    private void writeOneLayer(OptimizeSession s) throws IOException {
        int l = s.writeIndex;
        String layerName = "layers/layer-" + l + ".tar";
        Path tarPath = s.tmp.resolve(layerName);
        Files.createDirectories(tarPath.getParent());
        byte[] buffer = new byte[properties.getIoBufferBytes()];
        boolean wroteAny;
        try (OutputStream fos = Files.newOutputStream(tarPath);
             TarStreamWriter tw = new TarStreamWriter(fos)) {
            wroteAny = writePassA(s, l, tw, buffer);
            wroteAny |= writePassB(s, l, tw, buffer);
            tw.finish();
        }
        if (wroteAny) {
            s.plan.layerPaths.add(layerName);
            s.plan.diffIds.add("sha256:" + sha256OfFile(tarPath, buffer));
        } else {
            Files.delete(tarPath);
        }
        s.writeIndex++;
    }

    private void finalizeOptimize(OptimizeSession s) throws IOException {
        for (Path spill : s.spillBySha.values()) {
            Files.deleteIfExists(spill);
        }
        writeManifest(s.tmp, s.plan);
        Files.move(s.tmp, s.output);
        s.tmp = null;

        List<OptimizeResult.Contribution> topLayers = new ArrayList<>();
        for (ImageReport.Contribution c : LayerAnalyzer.topLayerContributions(
                s.plan.layerReports, s.plan.overlay, properties.getTopContributors())) {
            topLayers.add(new OptimizeResult.Contribution(c.key(), c.bytes()));
        }
        List<OptimizeResult.Contribution> topPaths = new ArrayList<>();
        for (ImageReport.Contribution c : LayerAnalyzer.topPathContributions(
                s.plan.overlay, properties.getTopContributors())) {
            topPaths.add(new OptimizeResult.Contribution(c.key(), c.bytes()));
        }
        s.result = new OptimizeResult(s.output, s.plan.overlay.reclaimableBytes(),
                topLayers, topPaths);
    }

    /** pass A：补写父目录 + 存活原始文件（需要时 tee 到 spill）。 */
    private boolean writePassA(OptimizeSession s, int layerIndex, TarStreamWriter tw,
                               byte[] buffer) throws IOException {
        boolean wroteAny = false;
        TreeSet<String> synth = s.plan.synthDirs.get(layerIndex);
        if (synth != null) {
            for (String d : synth) {
                tw.putDirectory(d);
                wroteAny = true;
            }
        }
        Map<String, LayerEntry> origins = s.plan.keptOrigins.get(layerIndex);
        if (origins == null || origins.isEmpty()) {
            return wroteAny;
        }
        try (InputStream in = s.source.openLayer(s.layers.get(layerIndex));
             TarStreamReader tar = new TarStreamReader(in, buffer.length)) {
            TarEntryHeader h;
            while ((h = tar.nextEntry()) != null) {
                checkCancelled(s.cancelled);
                String p = LayerAnalyzer.normalizePath(h.name());
                LayerEntry kept = p == null ? null : origins.get(p);
                if (kept != null && isRegular(h)) {
                    String sha = kept.sha256();
                    OutputStream spillOut = null;
                    if (sha != null && s.plan.neededShas.contains(sha)
                            && !s.spillBySha.containsKey(sha)) {
                        Path spillPath = s.tmp.resolve("spill-" + sha);
                        s.spillBySha.put(sha, spillPath);
                        spillOut = Files.newOutputStream(spillPath);
                    }
                    try (OutputStream es = tw.putFile(p, kept.size())) {
                        InputStream content = tar.entryStream();
                        long remaining = kept.size();
                        while (remaining > 0) {
                            int n = content.read(buffer, 0,
                                    (int) Math.min(buffer.length, remaining));
                            if (n < 0) {
                                throw new IOException("truncated entry content: " + p);
                            }
                            es.write(buffer, 0, n);
                            if (spillOut != null) {
                                spillOut.write(buffer, 0, n);
                            }
                            remaining -= n;
                        }
                    } finally {
                        if (spillOut != null) {
                            spillOut.close();
                        }
                    }
                    wroteAny = true;
                }
                tar.skipEntry();
            }
        } catch (TarParseException e) {
            throw new IOException("corrupt layer during optimize: " + e.getMessage(), e);
        }
        return wroteAny;
    }

    /** pass B：目录/符号链接/硬链接/特殊条目 + 去重硬链接 + 物化文件。 */
    private boolean writePassB(OptimizeSession s, int layerIndex, TarStreamWriter tw,
                               byte[] buffer) throws IOException {
        Map<String, LayerEntry> finals = s.plan.keptFinal.get(layerIndex);
        Map<String, String> dups = s.plan.dupHardlinks.get(layerIndex);
        Map<String, String> mats = s.plan.materialize.get(layerIndex);
        Set<String> markers = s.plan.keptMarkers.get(layerIndex);
        if ((finals == null || finals.isEmpty()) && (dups == null || dups.isEmpty())
                && (mats == null || mats.isEmpty())
                && (markers == null || markers.isEmpty())) {
            return false;
        }
        boolean wroteAny = false;
        try (InputStream in = s.source.openLayer(s.layers.get(layerIndex));
             TarStreamReader tar = new TarStreamReader(in, buffer.length)) {
            TarEntryHeader h;
            while ((h = tar.nextEntry()) != null) {
                checkCancelled(s.cancelled);
                String p = LayerAnalyzer.normalizePath(h.name());
                if (p != null) {
                    if (markers != null && markers.contains(p)) {
                        try (OutputStream es = tw.putFile(p, 0)) {
                            // 空标记文件
                        }
                        wroteAny = true;
                    } else if (dups != null && dups.containsKey(p)) {
                        tw.putHardlink(p, dups.get(p));
                        wroteAny = true;
                    } else if (mats != null && mats.containsKey(p)) {
                        Path spill = s.spillBySha.get(mats.get(p));
                        if (spill == null) {
                            throw new IOException("missing spilled content for " + p);
                        }
                        long size = Files.size(spill);
                        try (OutputStream es = tw.putFile(p, size);
                             InputStream sin = Files.newInputStream(spill)) {
                            sin.transferTo(es);
                        }
                        wroteAny = true;
                    } else if (finals != null && finals.containsKey(p)) {
                        LayerEntry entry = finals.get(p);
                        switch (entry.type()) {
                            case DIRECTORY -> tw.putDirectory(p);
                            case SYMLINK -> tw.putSymlink(p, entry.linkTarget());
                            case HARDLINK -> tw.putHardlink(p, entry.linkTarget());
                            default -> tw.putSpecial(p, h.typeFlag());
                        }
                        wroteAny = true;
                    }
                }
                tar.skipEntry();
            }
        } catch (TarParseException e) {
            throw new IOException("corrupt layer during optimize: " + e.getMessage(), e);
        }
        return wroteAny;
    }

    private void writeManifest(Path tmp, Plan plan) throws IOException {
        Map<String, Object> config = new TreeMap<>();
        config.put("architecture", "amd64");
        config.put("os", "linux");
        config.put("config", Map.of());
        config.put("rootfs", Map.of("type", "layers", "diff_ids", plan.diffIds));
        MAPPER.writeValue(tmp.resolve("config.json").toFile(), config);

        Map<String, Object> manifestEntry = new TreeMap<>();
        manifestEntry.put("Config", "config.json");
        manifestEntry.put("RepoTags", List.of("optimized:latest"));
        manifestEntry.put("Layers", plan.layerPaths);
        MAPPER.writeValue(tmp.resolve("manifest.json").toFile(), List.of(manifestEntry));
    }

    // ---- 工具 ----

    /** 标记条目作用的目标路径（whiteout：被删路径；opaque：所在目录）。 */
    private static String markerTarget(LayerEntry marker) {
        if (marker.type() == EntryType.OPAQUE_MARKER) {
            String p = marker.path();
            int slash = p.lastIndexOf('/');
            return slash < 0 ? "" : p.substring(0, slash);
        }
        return whiteoutTarget(marker.path());
    }

    private static String whiteoutTarget(String whPath) {
        int slash = whPath.lastIndexOf('/');
        String base = slash < 0 ? whPath : whPath.substring(slash + 1);
        String name = base.substring(".wh.".length());
        return slash < 0 ? name : whPath.substring(0, slash + 1) + name;
    }

    private static boolean isRegular(TarEntryHeader h) {
        return h.typeFlag() == TarEntryHeader.TYPE_REGULAR
                || h.typeFlag() == TarEntryHeader.TYPE_REGULAR_ALT;
    }

    private static void addKeys(Set<String> out, Map<String, ?> map) {
        if (map != null) {
            out.addAll(map.keySet());
        }
    }

    private static String normalize(String p) {
        if (p == null) {
            return "";
        }
        String s = p;
        while (s.startsWith("./")) {
            s = s.substring(2);
        }
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        return s;
    }

    private static String sha256OfFile(Path file, byte[] buffer) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw new ScanCancelledException(false);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
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
