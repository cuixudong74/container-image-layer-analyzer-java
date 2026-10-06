package com.github.cuixudong74.containerimagelayeranalyzerjava.overlay;

import com.github.cuixudong74.containerimagelayeranalyzerjava.model.EntryType;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.LayerEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 叠加引擎：按层序应用条目，还原 overlay 语义。
 *
 * <p>语义规则：
 * <ul>
 *   <li>后层同路径条目覆盖先层（文件/目录/链接互相替换时，旧子树整体失效）</li>
 *   <li>{@code .wh.<name>} 删除标记：删除同目录下 {@code <name>} 及其子树</li>
 *   <li>{@code .wh..wh..opq} 不透明目录标记：隐藏该目录下所有来自更低层的条目</li>
 *   <li>硬链接：引用同镜像内已存在路径的内容，内容按 inode 引用计数，
 *       只有最后一个引用消失时内容字节才计入可回收</li>
 * </ul>
 *
 * <p>内存与最终文件系统大小成正比；所有统计增量维护，查询 O(1)。
 * 迭代顺序固定（TreeMap 路径字典序），与条目到达顺序无关的中间态不影响最终统计。
 */
public class OverlayEngine {
    /**
     * 最终文件系统中的一个节点。
     *
     * @param entry   胜出的条目
     * @param inodeId 内容 inode（无内容条目为 -1）
     */
    public record FinalNode(LayerEntry entry, long inodeId) {
    }

    /** 内容 inode：硬链接共享同一 inode。 */
    private static final class Inode {
        final long size;
        final int originLayer;
        /** 创建该 inode 的 REGULAR 条目。 */
        final LayerEntry origin;
        int refs;
        /** 已计入可回收（层内重复内容），失效时不再重复计数。 */
        boolean countedReclaimable;

        Inode(long size, int originLayer, LayerEntry origin) {
            this.size = size;
            this.originLayer = originLayer;
            this.origin = origin;
            this.refs = 0;
        }
    }

    private static final class Node {
        final LayerEntry entry;
        final long inodeId; // -1 表示无内容（目录/符号链接/坏硬链接等）

        Node(LayerEntry entry, long inodeId) {
            this.entry = entry;
            this.inodeId = inodeId;
        }
    }

    private static final String WHITEOUT_PREFIX = ".wh.";
    private static final String OPAQUE_NAME = ".wh..wh..opq";

    private final TreeMap<String, Node> fs = new TreeMap<>();
    private final Map<Long, Inode> inodes = new HashMap<>();
    private long nextInodeId;

    private long liveBytes;
    private long reclaimableBytes;
    private final Map<Integer, Long> reclaimByLayer = new HashMap<>();
    private final Map<String, Long> reclaimByPath = new HashMap<>();
    /** 每层内已见内容 sha -> 首个 inode，用于层内重复内容检测。 */
    private final Map<Integer, Map<String, Long>> layerContentIndex = new HashMap<>();

    /**
     * 应用一个条目。必须按层序（layerIndex 单调不减）调用。
     */
    public void apply(LayerEntry entry) {
        String path = entry.path();
        int slash = path.lastIndexOf('/');
        String base = slash < 0 ? path : path.substring(slash + 1);

        if (base.equals(OPAQUE_NAME)) {
            String dir = slash < 0 ? "" : path.substring(0, slash);
            applyOpaque(dir, entry.layerIndex());
            return;
        }
        if (base.startsWith(WHITEOUT_PREFIX)) {
            String targetName = base.substring(WHITEOUT_PREFIX.length());
            String target = slash < 0 ? targetName : path.substring(0, slash + 1) + targetName;
            // 删除标记只隐藏更低层的同名路径（同层条目胜出）
            removeSubtreeBelow(target, entry.layerIndex());
            // 删除标记自身不占最终文件系统，标记条目字节（通常为 0）计入可回收
            reclaimableBytes += entry.size();
            reclaimByLayer.merge(entry.layerIndex(), entry.size(), Long::sum);
            reclaimByPath.merge(path, entry.size(), Long::sum);
            return;
        }

        Node existing = fs.get(path);
        if (existing != null) {
            boolean existingIsDir = existing.entry.type() == EntryType.DIRECTORY;
            boolean newIsDir = entry.type() == EntryType.DIRECTORY;
            if (existingIsDir && !newIsDir) {
                removeSubtree(path); // 目录被非目录替换，子树失效
            } else {
                unlink(path, existing);
            }
        }

        switch (entry.type()) {
            case REGULAR -> {
                long id = nextInodeId++;
                Inode ino = new Inode(entry.size(), entry.layerIndex(), entry);
                ino.refs = 1;
                inodes.put(id, ino);
                liveBytes += entry.size();
                fs.put(path, new Node(entry, id));
                detectLayerDuplicate(entry, id);
            }
            case HARDLINK -> {
                long id = resolveHardlink(entry.linkTarget());
                if (id >= 0) {
                    inodes.get(id).refs++;
                    fs.put(path, new Node(entry, id));
                } else {
                    fs.put(path, new Node(entry, -1)); // 悬空硬链接：计 0 字节
                }
            }
            default -> fs.put(path, new Node(entry, -1));
        }
    }

    /** 最终文件系统快照：path -> 节点，按路径字典序。 */
    public List<Map.Entry<String, FinalNode>> finalFileSystem() {
        List<Map.Entry<String, FinalNode>> out = new ArrayList<>(fs.size());
        for (Map.Entry<String, Node> e : fs.entrySet()) {
            out.add(Map.entry(e.getKey(), new FinalNode(e.getValue().entry, e.getValue().inodeId)));
        }
        return out;
    }

    /**
     * 存活 inode（refs>0）的原始条目：inodeId -> 创建该 inode 的 REGULAR 条目。
     * 优化器据此判断哪些层条目必须保留。
     */
    public Map<Long, LayerEntry> liveInodeOrigins() {
        Map<Long, LayerEntry> out = new HashMap<>();
        for (Map.Entry<Long, Inode> e : inodes.entrySet()) {
            if (e.getValue().refs > 0 && e.getValue().origin != null) {
                out.put(e.getKey(), e.getValue().origin);
            }
        }
        return out;
    }

    /** 最终文件系统内容字节数（被至少一个最终路径引用的 inode 字节之和）。 */
    public long finalFileSystemBytes() {
        return liveBytes;
    }

    /** 可回收字节数：被覆盖/删除的内容 + 删除标记 + 层内重复内容。 */
    public long reclaimableBytes() {
        return reclaimableBytes;
    }

    /** 按层统计可回收字节（归因到产生浪费的层）。 */
    public Map<Integer, Long> reclaimableByLayer() {
        return Map.copyOf(reclaimByLayer);
    }

    /** 按路径统计可回收字节。 */
    public Map<String, Long> reclaimableByPath() {
        return Map.copyOf(reclaimByPath);
    }

    // ---- 内部 ----

    private void applyOpaque(String dir, int layerIndex) {
        String prefix = dir.isEmpty() ? "" : dir + "/";
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, Node> e : fs.entrySet()) {
            String p = e.getKey();
            if (!p.startsWith(prefix) || p.equals(dir)) {
                continue;
            }
            if (e.getValue().entry.layerIndex() < layerIndex) {
                toRemove.add(p);
            }
        }
        for (String p : toRemove) {
            unlink(p, fs.get(p));
            fs.remove(p);
        }
    }

    private void removeSubtree(String path) {
        removeSubtreeBelow(path, Integer.MAX_VALUE);
    }

    /** 删除路径子树中 layerIndex 严格小于 bound 的条目。 */
    private void removeSubtreeBelow(String path, int bound) {
        Node n = fs.get(path);
        if (n != null && n.entry.layerIndex() < bound) {
            unlink(path, n);
            fs.remove(path);
        }
        String prefix = path + "/";
        var tail = fs.tailMap(prefix, false);
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, Node> e : tail.entrySet()) {
            if (!e.getKey().startsWith(prefix)) {
                break;
            }
            if (e.getValue().entry.layerIndex() < bound) {
                toRemove.add(e.getKey());
            }
        }
        for (String p : toRemove) {
            unlink(p, fs.get(p));
            fs.remove(p);
        }
    }

    private void unlink(String path, Node node) {
        if (node.inodeId < 0) {
            return;
        }
        Inode ino = inodes.get(node.inodeId);
        if (--ino.refs == 0) {
            inodes.remove(node.inodeId);
            liveBytes -= ino.size;
            if (!ino.countedReclaimable) {
                reclaimableBytes += ino.size;
                reclaimByLayer.merge(ino.originLayer, ino.size, Long::sum);
                reclaimByPath.merge(path, ino.size, Long::sum);
            }
        }
    }

    private long resolveHardlink(String linkTarget) {
        if (linkTarget == null) {
            return -1;
        }
        String normalized = normalize(linkTarget);
        Node target = fs.get(normalized);
        if (target == null || target.inodeId < 0) {
            return -1;
        }
        return target.inodeId;
    }

    private void detectLayerDuplicate(LayerEntry entry, long inodeId) {
        String sha = entry.sha256();
        if (sha == null || entry.size() == 0) {
            return;
        }
        Map<String, Long> index = layerContentIndex.computeIfAbsent(
                entry.layerIndex(), k -> new HashMap<>());
        if (index.putIfAbsent(sha, inodeId) != null) {
            // 同层相同内容已存在：这份拷贝可通过硬链接去重回收
            Inode ino = inodes.get(inodeId);
            ino.countedReclaimable = true;
            reclaimableBytes += entry.size();
            reclaimByLayer.merge(entry.layerIndex(), entry.size(), Long::sum);
            reclaimByPath.merge(entry.path(), entry.size(), Long::sum);
        }
    }

    private static String normalize(String p) {
        String s = p;
        while (s.startsWith("./")) {
            s = s.substring(2);
        }
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        while (s.endsWith("/") && s.length() > 1) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
