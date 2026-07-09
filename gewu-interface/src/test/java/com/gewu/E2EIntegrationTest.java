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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端业务流程集成测试 — 使用 H2 内存数据库与 MockMvc 验证完整链路.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DirtiesContext
class E2EIntegrationTest {

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

    private static final MediaType JSON_UTF8 = new MediaType("application", "json", StandardCharsets.UTF_8);

    private byte[] jsonBytes(Object body) throws Exception {
        return objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
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

    @Test
    @DisplayName("完整业务链路: 注册→登录→创建项目→当前用户→创建会话→发送消息→消息列表→创建Agent→Agent列表→创建工作流")
    void fullBusinessFlow() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "e2e_" + suffix;
        String email = "e2e_" + suffix + "@gewu.com";

        var register = new java.util.HashMap<String, Object>();
        register.put("username", username);
        register.put("email", email);
        register.put("password", "passw0rd");
        register.put("displayName", "E2EUser");

        MvcResult registerResult = authPost("/api/v1/auth/register", register);
        JsonNode registerData = dataNode(registerResult.getResponse().getContentAsString());
        assertThat(registerData.path("username").asText()).isEqualTo(username);
        assertThat(registerData.path("accessToken").asText()).isNotBlank();
        assertThat(registerData.path("userId").asText()).isNotBlank();

        var login = new java.util.HashMap<String, Object>();
        login.put("username", username);
        login.put("password", "passw0rd");
        MvcResult loginResult = authPost("/api/v1/auth/login", login);
        JsonNode loginData = dataNode(loginResult.getResponse().getContentAsString());
        assertThat(loginData.path("accessToken").asText()).isNotBlank();
        String token = loginData.path("accessToken").asText();
        assertThat(loginData.path("userId").asText()).isEqualTo(registerData.path("userId").asText());

        var project = new java.util.HashMap<String, Object>();
        project.put("projectName", "E2EProject_" + suffix);
        project.put("description", "e2e test project");
        project.put("visibility", 0);
        MvcResult projectResult = authedPost(token, "/api/v1/projects", project);
        JsonNode projectData = dataNode(projectResult.getResponse().getContentAsString());
        assertThat(projectData.path("projectId").asText()).isNotBlank();
        assertThat(projectData.path("projectName").asText()).isEqualTo("E2EProject_" + suffix);
        assertThat(projectData.path("ownerId").asText()).isNotBlank();

        MvcResult meResult = authedGet(token, "/api/v1/users/me");
        JsonNode meData = dataNode(meResult.getResponse().getContentAsString());
        assertThat(meData.path("userId").asText()).isNotBlank();
        assertThat(meData.path("username").asText()).isEqualTo(username);

        var session = new java.util.HashMap<String, Object>();
        session.put("title", "E2ESession_" + suffix);
        session.put("type", 1);
        session.put("projectId", projectData.path("projectId").asText());
        MvcResult sessionResult = authedPost(token, "/api/v1/sessions", session);
        JsonNode sessionData = dataNode(sessionResult.getResponse().getContentAsString());
        assertThat(sessionData.path("sessionId").asText()).isNotBlank();
        String sessionId = sessionData.path("sessionId").asText();

        var message = new java.util.HashMap<String, Object>();
        message.put("messageType", "text");
        message.put("content", "hello e2e");
        MvcResult sendResult = authedPost(token, "/api/v1/sessions/" + sessionId + "/messages", message);
        JsonNode messageData = dataNode(sendResult.getResponse().getContentAsString());
        assertThat(messageData.path("messageId").asText()).isNotBlank();
        assertThat(messageData.path("content").asText()).isEqualTo("hello e2e");

        MvcResult listResult = authedGet(token, "/api/v1/sessions/" + sessionId + "/messages?page=1&size=20");
        JsonNode listData = dataNode(listResult.getResponse().getContentAsString());
        assertThat(listData.path("records").isArray()).isTrue();
        assertThat(listData.path("records").size()).isGreaterThanOrEqualTo(1);
        assertThat(listData.path("records").get(0).path("content").asText()).isEqualTo("hello e2e");

        var agent = new java.util.HashMap<String, Object>();
        agent.put("agentName", "E2EAgent_" + suffix);
        agent.put("description", "e2e test agent");
        agent.put("modelProvider", "deepseek");
        agent.put("modelName", "deepseek-chat");
        agent.put("systemPrompt", "you are a test assistant");
        MvcResult agentResult = authedPost(token, "/api/v1/agents", agent);
        JsonNode agentData = dataNode(agentResult.getResponse().getContentAsString());
        assertThat(agentData.path("agentId").asText()).isNotBlank();
        assertThat(agentData.path("modelProvider").asText()).isEqualTo("deepseek");

        MvcResult agentsResult = authedGet(token, "/api/v1/agents?page=1&size=20");
        JsonNode agentsData = dataNode(agentsResult.getResponse().getContentAsString());
        assertThat(agentsData.path("records").isArray()).isTrue();
        assertThat(agentsData.path("records").size()).isGreaterThanOrEqualTo(1);

        var workflow = new java.util.HashMap<String, Object>();
        workflow.put("workflowName", "E2EWorkflow_" + suffix);
        workflow.put("description", "e2e test workflow");
        workflow.put("category", "devops");
        MvcResult workflowResult = authedPost(token, "/api/v1/workflows", workflow);
        JsonNode workflowData = dataNode(workflowResult.getResponse().getContentAsString());
        assertThat(workflowData.path("workflowId").asText()).isNotBlank();
        assertThat(workflowData.path("version").asInt()).isEqualTo(1);
        assertThat(workflowData.path("status").asInt()).isEqualTo(0);
    }
}