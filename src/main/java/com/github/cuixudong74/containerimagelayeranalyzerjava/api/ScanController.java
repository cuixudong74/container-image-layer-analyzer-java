package com.github.cuixudong74.containerimagelayeranalyzerjava.api;

import com.github.cuixudong74.containerimagelayeranalyzerjava.model.TaskSnapshot;
import com.github.cuixudong74.containerimagelayeranalyzerjava.task.ScanTaskManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** REST endpoints for submitting scans, polling progress and fetching reports. */
@RestController
@RequestMapping("/api")
public class ScanController {

    private final ScanTaskManager tasks;

    public ScanController(ScanTaskManager tasks) {
        this.tasks = tasks;
    }

    public record ScanRequest(String path) {
    }

    public record OptimizeRequest(String path, String outputDir) {
    }

    @PostMapping("/scans")
    public Map<String, String> submitScan(@RequestBody ScanRequest req) {
        return Map.of("taskId", tasks.submitScan(Path.of(req.path())));
    }

    @PostMapping("/optimizations")
    public Map<String, String> submitOptimize(@RequestBody OptimizeRequest req) {
        return Map.of("taskId", tasks.submitOptimize(Path.of(req.path()), Path.of(req.outputDir())));
    }

    @GetMapping("/tasks")
    public List<TaskSnapshot> listTasks() {
        return tasks.listTasks();
    }

    @GetMapping("/tasks/{id}")
    public ResponseEntity<TaskSnapshot> status(@PathVariable String id) {
        return tasks.status(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/tasks/{id}/cancel")
    public ResponseEntity<Map<String, Boolean>> cancel(@PathVariable String id) {
        boolean accepted = tasks.cancel(id);
        return accepted ? ResponseEntity.ok(Map.of("cancelled", true))
                : ResponseEntity.notFound().build();
    }

    @GetMapping("/tasks/{id}/result")
    public ResponseEntity<Object> result(@PathVariable String id) {
        return tasks.result(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
