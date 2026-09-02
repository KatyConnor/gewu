package com.gewu.application.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.hitl.HumanDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SseBroadcastMessage} 序列化往返与路由语义测试（T5.1）。
 */
@DisplayName("SSE 跨实例广播消息")
class SseBroadcastMessageTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("消息 JSON 往返：字段完整保留")
    void jsonRoundTrip() throws Exception {
        SseBroadcastMessage message = new SseBroadcastMessage(
                "instance-a", SseBroadcastMessage.SCOPE_SESSION, "sess-1",
                "approval_required", "{\"approvalId\":\"ap-1\"}");

        String json = objectMapper.writeValueAsString(message);
        SseBroadcastMessage parsed = objectMapper.readValue(json, SseBroadcastMessage.class);

        assertEquals("instance-a", parsed.origin());
        assertEquals("session", parsed.scope());
        assertEquals("sess-1", parsed.target());
        assertEquals("approval_required", parsed.eventName());
        assertTrue(parsed.payload().contains("ap-1"));
    }

    @Test
    @DisplayName("origin 自环判定：发布实例的本地投递跳过依据")
    void originLoopGuard() {
        String self = "instance-self";
        SseBroadcastMessage own = new SseBroadcastMessage(self, "session", "s", "e", "{}");
        SseBroadcastMessage other = new SseBroadcastMessage("instance-other", "session", "s", "e", "{}");

        assertTrue(own.origin().equals(self));     // 自发 -> 跳过
        assertTrue(!other.origin().equals(self));  // 他发 -> 本地投递
    }

    @Test
    @DisplayName("HITL 决策载荷可反序列化为 HumanDecision")
    void hitlDecisionPayload() throws Exception {
        HumanDecision decision = HumanDecision.builder()
                .decision("APPROVED").value("同意上线").operatorId("admin").build();
        String payload = objectMapper.writeValueAsString(decision);

        HumanDecision parsed = objectMapper.readValue(payload, HumanDecision.class);
        assertEquals("APPROVED", parsed.getDecision());
        assertEquals("同意上线", parsed.getValue());

        // 决策信封（经 SseBroadcastService.relayDecision 包装后的完整消息）
        SseBroadcastMessage message = new SseBroadcastMessage(
                "instance-b", SseBroadcastMessage.SCOPE_HITL, "approval-9", null, payload);
        SseBroadcastMessage parsed2 = objectMapper.readValue(
                objectMapper.writeValueAsString(message), SseBroadcastMessage.class);
        HumanDecision roundTrip = objectMapper.readValue(parsed2.payload(), HumanDecision.class);
        assertEquals("admin", roundTrip.getOperatorId());
    }

    @Test
    @DisplayName("载荷 JSON 直接透传（字符串不二次序列化）")
    void stringPayloadPassthrough() {
        SseBroadcastService service = new SseBroadcastService(null, objectMapper);
        String payload = service.toJson(Map.of("k", "值"));
        assertTrue(payload.contains("\"k\"") && payload.contains("值"));

        // 字符串载荷原样返回（不产生带转义的二次包裹）
        assertEquals("{\"already\":\"json\"}", service.toJson("{\"already\":\"json\"}"));
    }
}
