package com.github.cuixudong74.containerimagelayeranalyzerjava.web;

import com.github.cuixudong74.containerimagelayeranalyzerjava.model.ImageReport;
import com.github.cuixudong74.containerimagelayeranalyzerjava.optimize.OptimizeResult;
import com.github.cuixudong74.containerimagelayeranalyzerjava.task.ScanTask;
import com.github.cuixudong74.containerimagelayeranalyzerjava.task.ScanTaskManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** REST 入口：提交/查询/取消扫描与优化任务。 */
@RestController
@RequestMapping("/api/tasks")
public class AnalyzerController {
    private final ScanTaskManager manager;

    public AnalyzerController(ScanTaskManager manager) {
        this.manager = manager;
    }

    public record TaskView(String id, String kind, String status, String input, String output,
                           long processedBytes, long totalBytes, long processedEntries,
                           int currentLayer, int totalLayers, double fraction,
                           String error) {
        static TaskView of(ScanTask t) {
            return new TaskView(t.getId(), t.getKind().name(), t.getStatus().name(),
                    String.valueOf(t.getInputPath()),
                    t.getOutputPath() == null ? null : String.valueOf(t.getOutputPath()),
                    t.getProgress().getProcessedBytes(), t.getProgress().getTotalBytes(),
                    t.getProgress().getProcessedEntries(),
                    t.getProgress().getCurrentLayerIndex(), t.getProgress().getTotalLayers(),
                    t.getProgress().fraction(), t.getErrorMessage());
        }
    }

    @PostMapping("/scan")
    public ResponseEntity<Map<String, String>> submitScan(@RequestParam String input) {
        String id = manager.submitScan(Path.of(input));
        return ResponseEntity.accepted().body(Map.of("taskId", id));
    }

    @PostMapping("/optimize")
    public ResponseEntity<Map<String, String>> submitOptimize(@RequestParam String input,
                                                              @RequestParam String output) {
        String id = manager.submitOptimize(Path.of(input), Path.of(output));
        return ResponseEntity.accepted().body(Map.of("taskId", id));
    }

    @GetMapping
    public List<TaskView> list() {
        return manager.listTasks().stream().map(TaskView::of).toList();
    }

    @GetMapping("/{id}")
    public TaskView get(@PathVariable String id) {
        return TaskView.of(requireTask(id));
    }

    @GetMapping("/{id}/report")
    public ImageReport report(@PathVariable String id) {
        requireTask(id);
        ImageReport report = manager.getReport(id);
        if (report == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "report not ready");
        }
        return report;
    }

    @GetMapping("/{id}/optimize-result")
    public OptimizeResult optimizeResult(@PathVariable String id) {
        requireTask(id);
        OptimizeResult result = manager.getOptimizeResult(id);
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "optimize result not ready");
        }
        return result;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> cancel(@PathVariable String id) {
        requireTask(id);
        return Map.of("cancelled", manager.cancel(id));
    }

    private ScanTask requireTask(String id) {
        ScanTask t = manager.getTask(id);
        if (t == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown task: " + id);
        }
        return t;
    }
}
