package com.github.cuixudong74.containerimagelayeranalyzerjava.config;

import com.github.cuixudong74.containerimagelayeranalyzerjava.task.ScanTaskManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 分析器 Bean 装配。 */
@Configuration
public class AnalyzerConfig {
    @Bean(destroyMethod = "close")
    public ScanTaskManager scanTaskManager(AnalyzerProperties properties) {
        return new ScanTaskManager(properties);
    }
}
