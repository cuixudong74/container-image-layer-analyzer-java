package com.github.cuixudong74.containerimagelayeranalyzerjava.report;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.cuixudong74.containerimagelayeranalyzerjava.model.ImageReport;

import java.io.IOException;
import java.io.OutputStream;

/**
 * 确定性报告输出。
 *
 * <p>同一个 {@link ImageReport} 对象在任何 JVM、任何并发度下序列化出
 * 逐字节一致的 JSON：字段顺序固定（声明序）、Map 按键排序、
 * 列表在构建时已按稳定规则排序、关闭科学计数法与平台相关行为。
 */
public final class ReportWriter {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private ReportWriter() {
    }

    public static void writeJson(ImageReport report, OutputStream out) throws IOException {
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(out, report);
    }

    public static byte[] toJsonBytes(ImageReport report) throws IOException {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(report);
    }
}
