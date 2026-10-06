package com.github.cuixudong74.containerimagelayeranalyzerjava.tests;

import com.github.cuixudong74.containerimagelayeranalyzerjava.tar.TarStreamWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 构造 docker save 布局的测试镜像目录。 */
final class TestImages {
    private TestImages() {
    }

    /** 单层构造器。 */
    static final class Layer {
        private final List<Entry> entries = new ArrayList<>();

        record Entry(char kind, String path, byte[] content, String target) {
        }

        Layer file(String path, String content) {
            return file(path, content.getBytes(StandardCharsets.UTF_8));
        }

        Layer file(String path, byte[] content) {
            entries.add(new Entry('f', path, content, null));
            return this;
        }

        Layer dir(String path) {
            entries.add(new Entry('d', path, null, null));
            return this;
        }

        Layer symlink(String path, String target) {
            entries.add(new Entry('s', path, null, target));
            return this;
        }

        Layer hardlink(String path, String target) {
            entries.add(new Entry('h', path, null, target));
            return this;
        }

        /** 删除标记：隐藏下层同名路径。 */
        Layer whiteout(String path) {
            int slash = path.lastIndexOf('/');
            String wh = slash < 0 ? ".wh." + path
                    : path.substring(0, slash + 1) + ".wh." + path.substring(slash + 1);
            entries.add(new Entry('f', wh, new byte[0], null));
            return this;
        }

        /** 不透明目录标记。 */
        Layer opaque(String dir) {
            entries.add(new Entry('f', dir + "/.wh..wh..opq", new byte[0], null));
            return this;
        }

        /** 任意原始条目（用于构造非法输入）。 */
        Layer raw(String path) {
            entries.add(new Entry('f', path, new byte[0], null));
            return this;
        }

        byte[] toTar() throws IOException {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try (TarStreamWriter tw = new TarStreamWriter(bos)) {
                for (Entry e : entries) {
                    switch (e.kind()) {
                        case 'f' -> {
                            try (OutputStream es = tw.putFile(e.path(), e.content().length)) {
                                es.write(e.content());
                            }
                        }
                        case 'd' -> tw.putDirectory(e.path());
                        case 's' -> tw.putSymlink(e.path(), e.target());
                        case 'h' -> tw.putHardlink(e.path(), e.target());
                        default -> throw new IllegalStateException();
                    }
                }
                tw.finish();
            }
            return bos.toByteArray();
        }
    }

    static Layer layer() {
        return new Layer();
    }

    /** 把若干层写成 docker save 布局目录。 */
    static Path writeImage(Path dir, List<Layer> layers) throws IOException {
        List<String> layerPaths = new ArrayList<>();
        for (int i = 0; i < layers.size(); i++) {
            String rel = "layer" + i + "/layer.tar";
            Path p = dir.resolve(rel);
            Files.createDirectories(p.getParent());
            Files.write(p, layers.get(i).toTar());
            layerPaths.add(rel);
        }
        StringBuilder manifest = new StringBuilder();
        manifest.append("[{\"Config\":\"config.json\",\"RepoTags\":[\"test:latest\"],\"Layers\":[");
        for (int i = 0; i < layerPaths.size(); i++) {
            if (i > 0) {
                manifest.append(',');
            }
            manifest.append('\"').append(layerPaths.get(i)).append('\"');
        }
        manifest.append("]}]");
        Files.writeString(dir.resolve("manifest.json"), manifest.toString());
        Files.writeString(dir.resolve("config.json"), "{}");
        return dir;
    }
}
