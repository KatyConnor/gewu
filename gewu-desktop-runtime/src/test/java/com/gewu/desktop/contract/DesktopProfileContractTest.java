package com.gewu.desktop.contract;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D1-13 引擎 API Profile 契约测试：以 stub profile 跑真实端点，
 * 断言响应形状（键集合/事件序列）与 @gewu/agent-ui/protocol 定义一致。
 * <p>这是时间线空壳教训（D1-24）的制度化：协议漂移在 CI 拦截，而非终端环境暴露。
 *
 * @since 1.0.0
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("stub")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DesktopProfileContractTest {

    @Autowired
    private TestRestTemplate rest;

    private static String sessionId;

    // ==================== 引擎健康 ====================

    @Test
    @Order(1)
    void healthShape() {
        Map body = rest.getForObject("/api/v1/engine/health", Map.class);
        assertThat(body).isNotNull();
        assertThat(body.get("code")).isEqualTo(10000);
        Map data = (Map) body.get("data");
        assertThat(data).containsKeys("status", "module", "phase", "engine");
        assertThat(data.get("status")).isEqualTo("UP");
    }

    // ==================== 会话域 ====================

    @Test
    @Order(10)
    void createSessionShape() {
        Map body = post("/api/v1/ai/sessions", Map.of("title", "契约测试"));
        assertThat(body.get("code")).isEqualTo(10000);
        Map data = (Map) body.get("data");
        sessionId = String.valueOf(data.get("sessionId"));
        // 与 @gewu/agent-ui/protocol SessionDTO 对齐的键集合（含 null 字段——平台响应同构）
        assertThat(data).containsKeys(
                "sessionId", "title", "type", "typeDesc", "projectId", "status", "statusDesc",
                "isPublic", "pinned", "messageCount", "lastMessageAt", "agent", "directory",
                "workspaceId", "createdAt", "createdBy");
    }

    @Test
    @Order(11)
    void listSessionsShape() {
        Map body = rest.getForObject("/api/v1/ai/sessions", Map.class);
        assertThat(body.get("code")).isEqualTo(10000);
        List list = (List) body.get("data");
        assertThat(list).isNotNull();
        if (!list.isEmpty()) {
            assertThat((Map) list.get(0)).containsKeys("sessionId", "title", "status", "messageCount");
        }
    }

    // ==================== 聊天流式（事件序列契约） ====================

    @Test
    @Order(20)
    void chatStreamEventSequence() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"message":"契约测试消息","clientId":"contract-001"}""";
        String stream = rest.exchange("/api/v1/ai/chat/stream", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class).getBody();
        assertThat(stream).isNotNull();
        // 事件类型序列契约（ChatStreamEvent.type ∈ 平台定义集合）
        assertThat(stream).contains("\"type\":\"status\"");
        assertThat(stream).contains("\"type\":\"content\"");
        assertThat(stream).contains("\"type\":\"done\"");
        assertThat(stream).contains("finishReason");
        assertThat(stream).contains("[DONE]");
        // 模型解析回退契约：无显式 provider/model 时不得出现 MODEL_NOT_RESOLVED
        assertThat(stream).doesNotContain("MODEL_NOT_RESOLVED");
    }

    @Test
    @Order(21)
    void messagesShapeAfterChat() {
        // 依赖 chatStreamEventSequence 已落库（user+assistant 两条）
        Map msgBody = rest.getForObject("/api/v1/ai/sessions/" + sessionId + "/messages", Map.class);
        assertThat(msgBody.get("code")).isEqualTo(10000);
        List list = (List) msgBody.get("data");
        assertThat(list).isNotNull();
        if (!list.isEmpty()) {
            Map first = (Map) list.get(0);
            assertThat(first).containsKeys("id", "sessionId", "seq", "role", "content", "createdAt");
        }
    }

    @Test
    @Order(22)
    void chatStreamIdempotentReplay() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"message":"契约测试消息","clientId":"contract-001"}""";
        // 同 clientId 重放：消息计数不增（D-3 幂等语义）
        List before = ((Map) rest.getForObject("/api/v1/ai/sessions/" + sessionId + "/messages", Map.class)).get("data") != null
                ? (List) ((Map) rest.getForObject("/api/v1/ai/sessions/" + sessionId + "/messages", Map.class)).get("data")
                : List.of();
        rest.exchange("/api/v1/ai/chat/stream", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        List after = (List) ((Map) rest.getForObject("/api/v1/ai/sessions/" + sessionId + "/messages", Map.class)).get("data");
        assertThat(after.size()).isEqualTo(before.size());
    }

    // ==================== 模型与工具 ====================

    @Test
    @Order(30)
    void modelsShape() {
        Map body = rest.getForObject("/api/v1/ai/models", Map.class);
        assertThat(body.get("code")).isEqualTo(10000);
        List list = (List) body.get("data");
        assertThat(list).isNotNull();
        if (!list.isEmpty()) {
            assertThat((Map) list.get(0)).containsKeys(
                    "provider", "modelName", "displayName", "supported");
        }
    }

    @Test
    @Order(31)
    void localToolsRegistered() {
        Map body = rest.getForObject("/api/v1/engine/tools", Map.class);
        assertThat(body.get("code")).isEqualTo(10000);
        List list = (List) body.get("data");
        assertThat(list).isNotNull();
        // @ToolProvider 本地工具契约（内置文件工具经 FileWorkspaceSpi 独立注册）
        assertThat(list).contains("shell.exec", "fs.glob", "fs.grep", "git.status", "project.context");
    }

    // ==================== 工具方法 ====================

    private Map post(String path, Object payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(payload, headers), Map.class).getBody();
    }

    private static final Set<String> IGNORED = Set.of();
}
