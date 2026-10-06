package com.github.cuixudong74.containerimagelayeranalyzerjava.analyzer;

/** Receives streaming progress callbacks during a scan. */
public interface ProgressListener {
    void onLayerStart(int layerIndex, String layerId);
    void onBytesScanned(long bytes);
    void onLayerDone(int layerIndex);
    /** Returns false to request cancellation (cooperative). */
    boolean isCancelled();
}
