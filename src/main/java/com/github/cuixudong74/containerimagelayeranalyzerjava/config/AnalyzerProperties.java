package com.github.cuixudong74.containerimagelayeranalyzerjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 分析器配置项。所有内存相关配置都有明确上界。
 */
@ConfigurationProperties(prefix = "analyzer")
public class AnalyzerProperties {
    /** 流式读缓冲区大小（字节），扫描单层的内存上界主要由它决定。 */
    private int ioBufferBytes = 64 * 1024;
    /** 单个条目元数据允许的最大路径长度。 */
    private int maxPathLength = 4096;
    /** 扫描线程池大小（并发任务共享的执行资源）。 */
    private int workerThreads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    /** 单任务超时（毫秒），<=0 表示不限制。 */
    private long taskTimeoutMillis = 10 * 60 * 1000L;
    /** 报告中主要贡献者（层/路径）条数上限。 */
    private int topContributors = 20;

    public int getIoBufferBytes() { return ioBufferBytes; }
    public void setIoBufferBytes(int v) { this.ioBufferBytes = v; }
    public int getMaxPathLength() { return maxPathLength; }
    public void setMaxPathLength(int v) { this.maxPathLength = v; }
    public int getWorkerThreads() { return workerThreads; }
    public void setWorkerThreads(int v) { this.workerThreads = v; }
    public long getTaskTimeoutMillis() { return taskTimeoutMillis; }
    public void setTaskTimeoutMillis(long v) { this.taskTimeoutMillis = v; }
    public int getTopContributors() { return topContributors; }
    public void setTopContributors(int v) { this.topContributors = v; }
}
