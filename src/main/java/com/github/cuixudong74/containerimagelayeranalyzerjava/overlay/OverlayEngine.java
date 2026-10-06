package com.github.cuixudong74.containerimagelayeranalyzerjava.overlay;

import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Applies layer entries in order and maintains the merged final filesystem,
 * honouring OCI/Docker overlay semantics:
 *
 * <ul>
 *   <li>a later entry at the same path overrides the earlier one;</li>
 *   <li>directories merge: re-creating a directory does not delete children
 *       contributed by lower layers;</li>
 *   <li>replacing a directory by a non-directory removes its whole subtree;</li>
 *   <li>whiteout entries ({@code .wh.<name>}) delete the target path and its
 *       subtree from lower layers;</li>
 *   <li>opaque markers ({@code .wh..wh..opq}) hide all lower-layer children of
 *       the directory while keeping entries added in the same or later layers;</li>
 *   <li>hardlinks reference content of another path and add no new content.</li>
 * </ul>
 *
 * <p>Bytes of every node removed from the merged view (overridden, whited-out
 * or hidden by an opaque marker) are accounted as reclaimable, attributed to
 * the layer that originally contributed them and to the removed path.
 *
 * <p>All returned collections are sorted by path, so results are stable
 * regardless of the order in which layers were applied.
 */
public final class OverlayEngine {

    /** Merged filesystem view: path -> node. TreeMap keeps deterministic order. */
    private final TreeMap<String, FsNode> fs = new TreeMap<>();

    /** Reclaimable bytes attributed to the contributing (lower) layer. */
    private final TreeMap<Integer, Long> reclaimableByLayer = new TreeMap<>();

    /** Reclaimable bytes attributed to the removed path. */
    private final TreeMap<String, Long> reclaimableByPath = new TreeMap<>();

    /** Records a regular entry (file/dir/symlink/hardlink) of the given layer. */
    public void apply(int layerIndex, String path, FsNode node) {
        if (path.isEmpty()) {
            return; // archive root itself
        }
        FsNode prev = fs.get(path);
        if (prev != null) {
            if (prev.type() == com.github.cuixudong74.containerimagelayeranalyzerjava.model.EntryType.DIRECTORY
                    && node.type() == com.github.cuixudong74.containerimagelayeranalyzerjava.model.EntryType.DIRECTORY) {
                // Directory merge: keep children, refresh the dir node itself.
                account(prev);
                fs.put(path, node);
                return;
            }
            evict(path, prev);
            if (prev.type() == com.github.cuixudong74.containerimagelayeranalyzerjava.model.EntryType.DIRECTORY) {
                removeSubtree(path);
            }
        }
        fs.put(path, node);
    }

    /** Records a whiteout for the given target path (already normalized). */
    public void applyWhiteout(int layerIndex, String targetPath) {
        FsNode prev = fs.get(targetPath);
        if (prev != null) {
            evict(targetPath, prev);
            if (prev.type() == com.github.cuixudong74.containerimagelayeranalyzerjava.model.EntryType.DIRECTORY) {
                removeSubtree(targetPath);
            }
        }
        // Whiteout of a non-existent path is a no-op.
    }

    /** Records an opaque-directory marker: hides lower-layer children of dirPath. */
    public void applyOpaqueDir(int layerIndex, String dirPath) {
        removeSubtree(dirPath);
    }

    /** Merged filesystem snapshot, sorted by path. */
    public Map<String, FsNode> finalFilesystem() {
        return Collections.unmodifiableMap(new TreeMap<>(fs));
    }

    /** Reclaimable bytes per contributing layer index, ascending. */
    public Map<Integer, Long> reclaimableByLayer() {
        return Collections.unmodifiableMap(new TreeMap<>(reclaimableByLayer));
    }

    /** Reclaimable bytes per removed path, sorted by path. */
    public Map<String, Long> reclaimableByPath() {
        return Collections.unmodifiableMap(new TreeMap<>(reclaimableByPath));
    }

    /** Total reclaimable bytes. */
    public long totalReclaimableBytes() {
        long sum = 0;
        for (long v : reclaimableByLayer.values()) {
            sum += v;
        }
        return sum;
    }

    // ------------------------------------------------------------------

    private void evict(String path, FsNode node) {
        fs.remove(path);
        account(node);
    }

    private void account(FsNode node) {
        if (node.size() <= 0) {
            return;
        }
        reclaimableByLayer.merge(node.originLayerIndex(), node.size(), Long::sum);
        reclaimableByPath.merge(node.path(), node.size(), Long::sum);
    }

    /** Removes all strict descendants of dirPath, accounting their bytes. */
    private void removeSubtree(String dirPath) {
        String prefix = dirPath + "/";
        NavigableMap<String, FsNode> tail = fs.tailMap(prefix, true);
        var it = tail.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (!e.getKey().startsWith(prefix)) {
                break;
            }
            account(e.getValue());
            it.remove();
        }
    }
}
