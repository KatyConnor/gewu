package com.veloflow.engine.runtime.handler;

import com.veloflow.engine.persistence.model.Workflow;
import com.veloflow.engine.persistence.model.WorkflowNode;
import com.veloflow.engine.persistence.model.WorkflowNodeInstance;
import com.veloflow.engine.runtime.WorkflowNodeContext;
import com.veloflow.engine.runtime.WorkflowNodeHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 同步响应节点测试（53 号 §3.3）：payload 覆写 / 上游透传。
 */
class RespondHandlerTest {

    @Test
    @DisplayName("config.payload 存在：输出为 payload JSON")
    void payloadOverridesUpstream() {
        RespondHandler handler = new RespondHandler();
        WorkflowNodeContext context = mock(WorkflowNodeContext.class);
        Map<String, Object> config = new HashMap<>();
        config.put("payload", Map.of("code", 0, "msg", "ok"));
        whenConfig(context, config, "{\"prev\":1}");

        handler.activate(context);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(context).complete(captor.capture());
        try {
            com.fasterxml.jackson.databind.JsonNode tree =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(captor.getValue());
            assertEquals(0, tree.get("code").asInt());
            assertEquals("ok", tree.get("msg").asText());
        } catch (Exception e) {
            throw new IllegalStateException("payload 非 JSON: " + captor.getValue(), e);
        }
    }

    @Test
    @DisplayName("缺省 payload：透传上游输出")
    void passesThroughUpstreamOutput() {
        RespondHandler handler = new RespondHandler();
        WorkflowNodeContext context = mock(WorkflowNodeContext.class);
        whenConfig(context, Map.of(), "{\"prev\":1}");

        handler.activate(context);

        verify(context).complete("{\"prev\":1}");
    }

    @Test
    @DisplayName("消息等待节点：登记 messageKey 并为等待形态")
    void receiveMessageRegistersKey() {
        ReceiveMessageHandler handler = new ReceiveMessageHandler();
        WorkflowNodeContext context = mock(WorkflowNodeContext.class);
        WorkflowNodeInstance nodeInstance = new WorkflowNodeInstance();
        org.mockito.Mockito.when(context.config()).thenReturn(Map.of("messageKey", "order-001"));
        org.mockito.Mockito.when(context.nodeInstance()).thenReturn(nodeInstance);

        handler.activate(context);

        assertEquals("order-001", nodeInstance.getMessageKey());
        assertEquals(WorkflowNodeHandler.NodeKind.WAITING, handler.kind());
        assertTrue(handler.requiredConfigFields().contains("messageKey"));
    }

    private void whenConfig(WorkflowNodeContext context, Map<String, Object> config, String upstream) {
        org.mockito.Mockito.when(context.config()).thenReturn(config);
        org.mockito.Mockito.when(context.upstreamOutput()).thenReturn(upstream);
    }
}
