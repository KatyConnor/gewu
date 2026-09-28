package com.veloflow.engine.runtime.handler;

import com.veloflow.engine.ai.FlowAiBridge;
import com.veloflow.engine.persistence.model.WorkflowInstance;
import com.veloflow.engine.runtime.WorkflowNodeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 节点 Handler 测试（51 号 §2.5 P4）：桥未接入可读失败 / 模板渲染贯通 /
 * 输出契约结构 / orchestration 非 success 转失败。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiNodeHandlerTest {

    @Mock FlowAiBridge bridge;
    @Mock WorkflowNodeContext context;
    @Mock WorkflowInstance instance;

    private LlmHandler llmHandler;
    private OrchestrationNodeHandler orchestrationHandler;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<FlowAiBridge> provider = org.mockito.Mockito.mock(ObjectProvider.class);
        lenient().when(provider.getIfAvailable()).thenReturn(bridge);
        llmHandler = new LlmHandler(provider);
        orchestrationHandler = new OrchestrationNodeHandler(provider);
        lenient().when(context.instance()).thenReturn(instance);
        lenient().when(instance.getId()).thenReturn("inst-1");
    }

    @Test
    @DisplayName("LLM 节点：prompt 模板渲染后调用桥，输出含 content/totalTokens")
    void llmRendersPromptAndOutputsContract() {
        when(context.config()).thenReturn(Map.of(
                "modelProvider", "zhipu",
                "promptTemplate", "订单 ${order} 是否异常？",
                "temperature", 0.3));
        when(context.variables()).thenReturn(Map.of("order", "A001"));
        when(bridge.invokeLlm(any())).thenReturn(new FlowAiBridge.LlmResult("正常", 42, "glm-4"));

        llmHandler.activate(context);

        ArgumentCaptor<FlowAiBridge.LlmSpec> captor = ArgumentCaptor.forClass(FlowAiBridge.LlmSpec.class);
        verify(bridge).invokeLlm(captor.capture());
        assertEquals("订单 A001 是否异常？", captor.getValue().prompt());
        assertEquals(0.3, captor.getValue().temperature());
        verify(context).complete("{\"content\":\"正常\",\"model\":\"glm-4\",\"totalTokens\":42}");
    }

    @Test
    @DisplayName("AI 桥未接入：节点以可读错误完成（不抛异常中断调度）")
    void missingBridgeCompletesWithReadableFailure() {
        @SuppressWarnings("unchecked")
        ObjectProvider<FlowAiBridge> empty = org.mockito.Mockito.mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        LlmHandler handler = new LlmHandler(empty);
        when(context.config()).thenReturn(Map.of("modelProvider", "zhipu", "promptTemplate", "p"));

        handler.activate(context);

        ArgumentCaptor<Boolean> success = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<String> output = ArgumentCaptor.forClass(String.class);
        verify(context).complete(success.capture(), output.capture());
        assertEquals(false, success.getValue());
        assertTrue(output.getValue().contains("AI 桥未接入"));
    }

    @Test
    @DisplayName("orchestration 节点：input 模板渲染贯通，非 success 状态转节点失败")
    void orchestrationFailureCompletesFailed() {
        when(context.config()).thenReturn(Map.of("graphId", "g-1", "inputTemplate", "in-${k}"));
        when(context.variables()).thenReturn(Map.of("k", "v1"));
        when(bridge.invokeOrchestration(org.mockito.ArgumentMatchers.eq("g-1"),
                org.mockito.ArgumentMatchers.eq("in-v1"),
                org.mockito.ArgumentMatchers.eq("inst-1")))
                .thenReturn(new FlowAiBridge.OrchestrationResult("failed", "exec-1", null, "图执行超时"));

        orchestrationHandler.activate(context);

        ArgumentCaptor<Boolean> success = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<String> output = ArgumentCaptor.forClass(String.class);
        verify(context).complete(success.capture(), output.capture());
        assertEquals(false, success.getValue());
        assertTrue(output.getValue().contains("图执行超时"));
    }

    @Test
    @DisplayName("orchestration 节点：success 状态输出 status/executionId/output 契约")
    void orchestrationSuccessOutputsContract() {
        when(context.config()).thenReturn(Map.of("graphId", "g-1"));
        when(context.variables()).thenReturn(Map.of());
        when(bridge.invokeOrchestration(any(), any(), any()))
                .thenReturn(new FlowAiBridge.OrchestrationResult("success", "exec-9", "{\"r\":1}", null));

        orchestrationHandler.activate(context);

        verify(context).complete(
                "{\"status\":\"success\",\"executionId\":\"exec-9\",\"output\":\"{\\\"r\\\":1}\"}");
    }
}
