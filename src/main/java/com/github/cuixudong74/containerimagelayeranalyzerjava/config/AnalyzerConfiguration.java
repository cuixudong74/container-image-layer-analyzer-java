package com.github.cuixudong74.containerimagelayeranalyzerjava.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.cuixudong74.containerimagelayeranalyzerjava.analyzer.ImageAnalyzer;
import com.github.cuixudong74.containerimagelayeranalyzerjava.optimize.ImageOptimizer;
import com.github.cuixudong74.containerimagelayeranalyzerjava.report.ReportWriter;
import com.github.cuixudong74.containerimagelayeranalyzerjava.task.ScanTaskManager;

@Configuration
@EnableConfigurationProperties(AnalyzerProperties.class)
public class AnalyzerConfiguration {

    @Bean
    public ImageAnalyzer imageAnalyzer(AnalyzerProperties properties) {
        return new ImageAnalyzer(properties);
    }

    @Bean
    public ImageOptimizer imageOptimizer(AnalyzerProperties properties) {
        return new ImageOptimizer(properties);
    }

    @Bean
    public ReportWriter reportWriter() {
        return new ReportWriter();
    }

    @Bean(destroyMethod = "close")
    public ScanTaskManager scanTaskManager(AnalyzerProperties properties) {
        return new ScanTaskManager(properties);
    }
}
