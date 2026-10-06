package com.github.cuixudong74.containerimagelayeranalyzerjava.input;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * 展开后的镜像目录输入。支持两种布局：
 * <ul>
 *   <li>docker save 展开目录：manifest.json + 层 tar 文件</li>
 *   <li>OCI layout 目录：oci-layout + index.json + blobs/sha256/*</li>
 * </ul>
 * 层可能为 tar 或 tar+gzip，打开时按魔数自动识别。
 */
public class DirectoryImageSource implements ImageSource {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path root;
    private final List<LayerRef> layers;
    private final String imageId;

    public DirectoryImageSource(Path dir) throws IOException {
        this.root = dir;
        if (Files.isRegularFile(dir.resolve("manifest.json"))) {
            DockerManifest m = parseDockerManifest(dir.resolve("manifest.json"));
            this.layers = m.layers();
            this.imageId = m.imageId();
        } else if (Files.isRegularFile(dir.resolve("index.json"))) {
            OciManifest m = parseOciManifest(dir);
            this.layers = m.layers();
            this.imageId = m.imageId();
        } else {
            throw new IOException("unrecognized image directory (no manifest.json or index.json): " + dir);
        }
    }

    @Override
    public String imageId() {
        return imageId;
    }

    @Override
    public List<LayerRef> listLayers() {
        return layers;
    }

    @Override
    public InputStream openLayer(LayerRef layer) throws IOException {
        InputStream raw = Files.newInputStream(root.resolve(layer.location()));
        PushbackInputStream pb = new PushbackInputStream(raw, 2);
        byte[] magic = new byte[2];
        int n = pb.read(magic);
        if (n > 0) {
            pb.unread(magic, 0, n);
        }
        if (n == 2 && (magic[0] & 0xff) == 0x1f && (magic[1] & 0xff) == 0x8b) {
            return new GZIPInputStream(pb);
        }
        return pb;
    }

    @Override
    public void close() {
        // 目录输入不持有资源
    }

    // ---- manifest 解析 ----

    private record DockerManifest(List<LayerRef> layers, String imageId) {
    }

    private record OciManifest(List<LayerRef> layers, String imageId) {
    }

    private static DockerManifest parseDockerManifest(Path manifestFile) throws IOException {
        JsonNode root;
        try {
            root = MAPPER.readTree(manifestFile.toFile());
        } catch (IOException e) {
            throw new IOException("invalid manifest.json: " + e.getMessage(), e);
        }
        if (!root.isArray() || root.isEmpty()) {
            throw new IOException("manifest.json is not a non-empty array");
        }
        JsonNode first = root.get(0);
        List<LayerRef> layers = new ArrayList<>();
        JsonNode layersNode = first.get("Layers");
        if (layersNode == null || !layersNode.isArray()) {
            throw new IOException("manifest.json missing Layers array");
        }
        Path dir = manifestFile.getParent();
        for (JsonNode l : layersNode) {
            String loc = l.asText();
            validateRelative(loc);
            long size = Files.exists(dir.resolve(loc)) ? Files.size(dir.resolve(loc)) : -1;
            layers.add(new LayerRef(loc, size, loc));
        }
        String imageId = first.hasNonNull("Config") ? first.get("Config").asText() : "unknown";
        return new DockerManifest(List.copyOf(layers), imageId);
    }

    private static OciManifest parseOciManifest(Path dir) throws IOException {
        JsonNode index;
        try {
            index = MAPPER.readTree(dir.resolve("index.json").toFile());
        } catch (IOException e) {
            throw new IOException("invalid index.json: " + e.getMessage(), e);
        }
        JsonNode manifests = index.get("manifests");
        if (manifests == null || !manifests.isArray() || manifests.isEmpty()) {
            throw new IOException("index.json has no manifests");
        }
        String manifestDigest = manifests.get(0).get("digest").asText();
        Path manifestPath = blobPath(dir, manifestDigest);
        JsonNode manifest = MAPPER.readTree(manifestPath.toFile());
        JsonNode layersNode = manifest.get("layers");
        if (layersNode == null || !layersNode.isArray()) {
            throw new IOException("OCI manifest missing layers");
        }
        List<LayerRef> layers = new ArrayList<>();
        for (JsonNode l : layersNode) {
            String digest = l.get("digest").asText();
            Path p = blobPath(dir, digest);
            String rel = dir.relativize(p).toString();
            long size = Files.exists(p) ? Files.size(p) : -1;
            layers.add(new LayerRef(digest, size, rel));
        }
        return new OciManifest(List.copyOf(layers), manifestDigest);
    }

    private static Path blobPath(Path dir, String digest) throws IOException {
        // digest 形如 "sha256:abc..."
        int colon = digest.indexOf(':');
        if (colon <= 0 || colon == digest.length() - 1) {
            throw new IOException("malformed digest: " + digest);
        }
        String algo = digest.substring(0, colon);
        String hex = digest.substring(colon + 1);
        validateRelative(algo);
        validateRelative(hex);
        return dir.resolve("blobs").resolve(algo).resolve(hex);
    }

    private static void validateRelative(String p) throws IOException {
        if (p.isEmpty() || p.startsWith("/") || p.contains("..") || p.indexOf('\0') >= 0) {
            throw new IOException("unsafe path in manifest: " + p);
        }
    }
}
