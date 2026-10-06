package com.github.cuixudong74.containerimagelayeranalyzerjava.input;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** 镜像输入抽象：本地归档或展开后的目录。 */
public interface ImageSource extends AutoCloseable {
    /** 镜像标识（稳定，可复现）。 */
    String imageId() throws IOException;

    /** 按应用顺序（从底层到顶层）列出层。 */
    List<LayerRef> listLayers() throws IOException;

    /** 打开某一层的内容流（tar 或 tar.gz，由实现保证可顺序读取）。调用方负责关闭。 */
    InputStream openLayer(LayerRef layer) throws IOException;

    @Override
    void close() throws IOException;
}
