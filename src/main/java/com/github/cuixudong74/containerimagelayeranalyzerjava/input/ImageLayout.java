package com.github.cuixudong74.containerimagelayeranalyzerjava.input;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses docker-save ({@code manifest.json}) and OCI ({@code index.json} +
 * manifest blob) image layouts into an ordered list of layer references.
 * Pure metadata handling: only small JSON documents are held in memory.
 */
final class ImageLayout {

    record LayerRef(String id, String location, boolean gzipped) {
    }

    record Layout(String imageId, List<LayerRef> layers) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ImageLayout() {
    }

    /** docker save layout: manifest.json is an array; the first image is used. */
    static Layout parseDockerManifest(byte[] json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        if (!root.isArray() || root.isEmpty()) {
            throw new IOException("manifest.json is not a non-empty array");
        }
        JsonNode img = root.get(0);
        String config = img.path("Config").asText("");
        String imageId = config.endsWith(".json") ? config.substring(0, config.length() - ".json".length()) : config;
        List<LayerRef> layers = new ArrayList<>();
        for (JsonNode l : img.path("Layers")) {
            String loc = l.asText();
            String id = loc.contains("/") ? loc.substring(0, loc.indexOf('/')) : loc;
            layers.add(new LayerRef(id, loc, false));
        }
        if (layers.isEmpty()) {
            throw new IOException("manifest.json lists no layers");
        }
        return new Layout(imageId, layers);
    }

    /** OCI layout: index.json points at a manifest blob which lists layer blobs. */
    static Layout parseOci(byte[] indexJson, BlobReader blobs) throws IOException {
        JsonNode index = MAPPER.readTree(indexJson);
        JsonNode manifests = index.path("manifests");
        if (!manifests.isArray() || manifests.isEmpty()) {
            throw new IOException("index.json has no manifests");
        }
        String manifestDigest = manifests.get(0).path("digest").asText("");
        if (manifestDigest.isEmpty()) {
            throw new IOException("index.json manifest has no digest");
        }
        byte[] manifestBytes = blobs.read(digestToPath(manifestDigest));
        if (manifestBytes == null) {
            throw new IOException("manifest blob not found: " + manifestDigest);
        }
        JsonNode manifest = MAPPER.readTree(manifestBytes);
        List<LayerRef> layers = new ArrayList<>();
        for (JsonNode l : manifest.path("layers")) {
            String digest = l.path("digest").asText("");
            String mediaType = l.path("mediaType").asText("");
            if (digest.isEmpty()) {
                throw new IOException("layer entry without digest");
            }
            boolean gz = mediaType.endsWith("+gzip") || mediaType.endsWith(".gzip");
            layers.add(new LayerRef(digest, digestToPath(digest), gz));
        }
        if (layers.isEmpty()) {
            throw new IOException("OCI manifest lists no layers");
        }
        return new Layout(manifestDigest, layers);
    }

    static String digestToPath(String digest) {
        // "sha256:abc..." -> "blobs/sha256/abc..."
        int colon = digest.indexOf(':');
        if (colon < 0) {
            return digest;
        }
        return "blobs/" + digest.substring(0, colon) + "/" + digest.substring(colon + 1);
    }

    /** Reads a small blob (manifest/index) by location; null when absent. */
    interface BlobReader {
        byte[] read(String location) throws IOException;
    }
}
