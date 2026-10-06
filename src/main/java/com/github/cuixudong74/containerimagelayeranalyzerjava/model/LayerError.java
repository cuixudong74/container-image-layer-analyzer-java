package com.github.cuixudong74.containerimagelayeranalyzerjava.model;

/** 某一层的错误信息。 */
public record LayerError(int layerIndex, String layerId, ErrorReason reason, String message) {
}
