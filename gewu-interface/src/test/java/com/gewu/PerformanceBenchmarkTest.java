package com.gewu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.interfaceconfig.security.XssFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 性能基准测试 — 基于 MockMvc 与 H2 内存数据库测量核心 API 的执行耗时.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DirtiesContext
class PerformanceBenchmarkTest {

    @TestConfiguration
    static class NoEscapeXssConfig {
        @Bean
        XssFilter xssFilter() {
            return new XssFilter() {
                @Override
                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                FilterChain filterChain) throws ServletException, java.io.IOException {
                    filterChain.doFilter(request, response);
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final MediaType JSON_UTF8 = new MediaType("application", "json", StandardCharsets.UTF_8);

    private byte[] jsonBytes(Object body) throws Exception {
        return objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
    }

    private String token(String body) throws Exception {
        JsonNode node = objectMapper.readTree(body);
        assertThat(node.path("code").asInt()).isEqualTo(10000);
        return node.path("data").path("accessToken").asText();
    }

    private JsonNode dataNode(String body) throws Exception {
        JsonNode node = objectMapper.readTree(body);
        assertThat(node.path("code").asInt()).isEqualTo(10000);
        return node.path("data");
    }

    private MvcResult authPost(String path, Object body) throws Exception {
        return mockMvc.perform(post(path)
                        .contentType(JSON_UTF8)
                        .content(jsonBytes(body)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private MvcResult authedPost(String token, String path, Object body) throws Exception {
        return mockMvc.perform(post(path)
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON_UTF8)
                        .content(jsonBytes(body)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private MvcResult authedGet(String token, String path) throws Exception {
        return mockMvc.perform(get(path)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
    }

    private Map<String, Object> registerBody(String suffix) {
        Map<String, Object> register = new HashMap<>();
        register.put("username", "bench_" + suffix);
        register.put("email", "bench_" + suffix + "@gewu.com");
        register.put("password", "passw0rd");
        register.put("displayName", "BenchUser");
        return register;
    }

    private Map<String, Object> loginBody(String suffix) {
        Map<String, Object> login = new HashMap<>();
        login.put("username", "bench_" + suffix);
        login.put("password", "passw0rd");
        return login;
    }

    private String register(String suffix) throws Exception {
        MvcResult r = authPost("/api/v1/auth/register", registerBody(suffix));
        return token(r.getResponse().getContentAsString());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private void measure(String name, int iterations, ThrowingRunnable action, double p95LimitMs) throws Exception {
        long[] times = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            action.run();
            times[i] = System.nanoTime() - start;
        }
        Arrays.sort(times);
        long sumNanos = 0;
        for (long t : times) sumNanos += t;
        double avgMs = (sumNanos / (double) iterations) / 1_000_000.0;
        double minMs = times[0] / 1_000_000.0;
        double maxMs = times[iterations - 1] / 1_000_000.0;
        double p95Ms = times[(int) Math.ceil(iterations * 0.95) - 1] / 1_000_000.0;
        double p99Ms = times[(int) Math.ceil(iterations * 0.99) - 1] / 1_000_000.0;
        System.out.printf("[%-18s] avg=%8.2fms min=%8.2fms max=%8.2fms p95=%8.2fms p99=%8.2fms%n",
                name, avgMs, minMs, maxMs, p95Ms, p99Ms);
        assertThat(p95Ms)
                .as("%s P95 阈值 %.0fms", name, p95LimitMs)
                .isLessThan(p95LimitMs);
    }

    @Test
    @DisplayName("性能基准: 注册/登录/用户查询/项目/会话/消息/Agent/工作流 核心接口耗时")
    void coreApiPerformanceBenchmark() throws Exception {
        List<String> tokens = new ArrayList<>();
        List<String> suffixes = new ArrayList<>();
        List<String> projectIds = new ArrayList<>();
        List<String> sessionIds = new ArrayList<>();
        AtomicInteger counter = new AtomicInteger(0);

        String seedSuffix = "seed_" + UUID.randomUUID().toString().substring(0, 6);
        tokens.add(register(seedSuffix));
        suffixes.add(seedSuffix);

        measure("注册", 100, () -> {
            String suffix = String.format("reg_%03d_", counter.getAndIncrement()) + UUID.randomUUID().toString().substring(0, 4);
            MvcResult r = authPost("/api/v1/auth/register", registerBody(suffix));
            assertThat(dataNode(r.getResponse().getContentAsString()).path("username").asText()).isNotBlank();
        }, 500);

        for (int i = 0; i < 100; i++) {
            String suffix = String.format("login_%03d", i);
            register(suffix);
            suffixes.add(suffix);
        }

        measure("登录", 100, () -> {
            String suffix = suffixes.get((int) (Math.random() * suffixes.size()));
            MvcResult r = authPost("/api/v1/auth/login", loginBody(suffix));
            String t = token(r.getResponse().getContentAsString());
            tokens.add(t);
        }, 500);

        measure("获取当前用户", 500, () -> {
            String t = tokens.get((int) (Math.random() * tokens.size()));
            MvcResult r = authedGet(t, "/api/v1/users/me");
            assertThat(dataNode(r.getResponse().getContentAsString()).path("userId").asText()).isNotBlank();
        }, 200);

        measure("创建项目", 50, () -> {
            String t = tokens.get((int) (Math.random() * tokens.size()));
            Map<String, Object> project = new HashMap<>();
            project.put("projectName", "BenchProject_" + UUID.randomUUID().toString().substring(0, 8));
            project.put("description", "perf");
            project.put("visibility", 0);
            MvcResult r = authedPost(t, "/api/v1/projects", project);
            String pid = dataNode(r.getResponse().getContentAsString()).path("projectId").asText();
            projectIds.add(pid);
        }, 500);

        measure("创建会话", 50, () -> {
            String t = tokens.get((int) (Math.random() * tokens.size()));
            Map<String, Object> session = new HashMap<>();
            session.put("title", "BenchSession_" + UUID.randomUUID().toString().substring(0, 8));
            session.put("type", 1);
            if (!projectIds.isEmpty()) {
                session.put("projectId", projectIds.get((int) (Math.random() * projectIds.size())));
            }
            MvcResult r = authedPost(t, "/api/v1/sessions", session);
            sessionIds.add(dataNode(r.getResponse().getContentAsString()).path("sessionId").asText());
        }, 500);

        for (int i = 0; i < 10; i++) {
            String t = tokens.get(i % tokens.size());
            Map<String, Object> session = new HashMap<>();
            session.put("title", "SeedSession_" + i);
            session.put("type", 1);
            if (!projectIds.isEmpty()) {
                session.put("projectId", projectIds.get(i % projectIds.size()));
            }
            MvcResult r = authedPost(t, "/api/v1/sessions", session);
            sessionIds.add(dataNode(r.getResponse().getContentAsString()).path("sessionId").asText());
        }

        measure("发送消息", 200, () -> {
            String t = tokens.get((int) (Math.random() * tokens.size()));
            String sid = sessionIds.get((int) (Math.random() * sessionIds.size()));
            Map<String, Object> message = new HashMap<>();
            message.put("messageType", "text");
            message.put("content", "perf-msg-" + UUID.randomUUID());
            authedPost(t, "/api/v1/sessions/" + sid + "/messages", message);
        }, 500);

        measure("创建Agent", 50, () -> {
            String t = tokens.get((int) (Math.random() * tokens.size()));
            Map<String, Object> agent = new HashMap<>();
            agent.put("agentName", "BenchAgent_" + UUID.randomUUID().toString().substring(0, 8));
            agent.put("description", "perf");
            agent.put("modelProvider", "deepseek");
            agent.put("modelName", "deepseek-chat");
            agent.put("systemPrompt", "perf assistant");
            authedPost(t, "/api/v1/agents", agent);
        }, 500);

        measure("创建工作流", 50, () -> {
            String t = tokens.get((int) (Math.random() * tokens.size()));
            Map<String, Object> workflow = new HashMap<>();
            workflow.put("workflowName", "BenchWorkflow_" + UUID.randomUUID().toString().substring(0, 8));
            workflow.put("description", "perf");
            workflow.put("category", "devops");
            authedPost(t, "/api/v1/workflows", workflow);
        }, 500);

        printSummary();
    }

    private void printSummary() {
        System.out.println();
        System.out.println("==================== 性能基准测试汇总 ====================");
        System.out.println("各步骤已打印: avg / min / max / P95 / P99 (ms)");
        System.out.println("断言: 查询接口 P95 < 200ms, 创建接口 P95 < 500ms");
        System.out.println("========================================================");
    }
}