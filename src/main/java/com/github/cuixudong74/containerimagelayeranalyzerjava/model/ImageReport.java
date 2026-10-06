package com.github.cuixudong74.containerimagelayeranalyzerjava.model;

import java.util.ArrayList;
import java.util.List;

/** 整镜像报告。所有列表在输出前按稳定规则排序，保证逐字节可复现。 */
public class ImageReport {
    /** 主要贡献者（层或路径）。 */
    public record Contribution(String key, long bytes) { }

    private String imageId;
    private final List<LayerReport> layers = new ArrayList<>();
    private long totalBytes;
    private long finalFileSystemBytes;
    private long reclaimableBytes;
    private final List<Contribution> topLayers = new ArrayList<>();
    private final List<Contribution> topPaths = new ArrayList<>();

    public String getImageId() { return imageId; }
    public void setImageId(String id) { this.imageId = id; }
    public List<LayerReport> getLayers() { return layers; }
    public long getTotalBytes() { return totalBytes; }
    public void setTotalBytes(long v) { this.totalBytes = v; }
    public long getFinalFileSystemBytes() { return finalFileSystemBytes; }
    public void setFinalFileSystemBytes(long v) { this.finalFileSystemBytes = v; }
    public long getReclaimableBytes() { return reclaimableBytes; }
    public void setReclaimableBytes(long v) { this.reclaimableBytes = v; }
    public List<Contribution> getTopLayers() { return topLayers; }
    public List<Contribution> getTopPaths() { return topPaths; }
}
