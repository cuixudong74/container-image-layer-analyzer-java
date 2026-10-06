package com.github.cuixudong74.containerimagelayeranalyzerjava.api;

import com.github.cuixudong74.containerimagelayeranalyzerjava.task.ScanTaskManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class ContextSmokeTest {

    @Autowired
    ScanTaskManager taskManager;

    @Test
    void contextStartsWithAllBeans() {
        assertNotNull(taskManager);
    }
}
