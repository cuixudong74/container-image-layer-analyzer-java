package com.github.cuixudong74.containerimagelayeranalyzerjava.model;

/** A non-fatal (skip) or fatal (failure) problem tied to a specific layer. */
public record LayerIssue(int layerIndex, String layerId, IssueReason reason, String path, String message) {
}
