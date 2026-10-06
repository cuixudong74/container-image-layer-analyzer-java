package com.github.cuixudong74.containerimagelayeranalyzerjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** REST 入口冒烟测试：提交 -> 轮询 -> 取报告 -> 取消。 */
@SpringBootTest
@AutoConfigureMockMvc
class WebLayerTest {
    @TempDir
    Path tmp;

    @Autowired
    MockMvc mvc;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void scanLifecycleOverHttp() throws Exception {
        Path image = TestImages.writeImage(tmp.resolve("img"), List.of(
                TestImages.layer().file("/a", "hello"),
                TestImages.layer().file("/a", "hi").whiteout("/none")));

        MvcResult submit = mvc.perform(post("/api/tasks/scan")
                        .param("input", image.toString()))
                .andExpect(status().isAccepted())
                .andReturn();
        String taskId = mapper.readTree(submit.getResponse().getContentAsString())
                .get("taskId").asText();

        // 轮询到终态
        String status = "";
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            MvcResult r = mvc.perform(get("/api/tasks/{id}", taskId))
                    .andExpect(status().isOk()).andReturn();
            JsonNode view = mapper.readTree(r.getResponse().getContentAsString());
            status = view.get("status").asText();
            if (!status.equals("PENDING") && !status.equals("RUNNING")) {
                break;
            }
            Thread.sleep(20);
        }
        assertEquals("SUCCEEDED", status);

        MvcResult report = mvc.perform(get("/api/tasks/{id}/report", taskId))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = mapper.readTree(report.getResponse().getContentAsString());
        assertEquals(2, body.get("finalFileSystemBytes").asLong());
        assertEquals(5, body.get("reclaimableBytes").asLong());

        // 取消终态任务返回 false
        MvcResult cancel = mvc.perform(delete("/api/tasks/{id}", taskId))
                .andExpect(status().isOk()).andReturn();
        assertTrue(mapper.readTree(cancel.getResponse().getContentAsString())
                .get("cancelled").isBoolean());

        // 未知任务 404
        mvc.perform(get("/api/tasks/{id}", "no-such-task"))
                .andExpect(status().isNotFound());
    }
}
