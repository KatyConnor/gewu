package com.gewu.agent.engine.orchestration;

import com.gewu.agent.engine.AgentEngineException;
import com.gewu.agent.engine.orchestration.model.GraphNode;
import com.gewu.agent.engine.orchestration.model.NodeType;
import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import com.gewu.agent.engine.tool.ToolContext;
import com.gewu.agent.engine.tool.ToolExecutor;
import com.gewu.agent.engine.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * {@link GraphNodeExecutor} TOOL 节点执行测试（T3.1）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("图节点执行器")
class GraphNodeExecutorTest {

    @Mock
    private ToolExecutor toolExecutor;

    private GraphNodeExecutor nodeExecutor;
    private OrchestrationContext ctx;

    @BeforeEach
    void setUp() {
        nodeExecutor = new GraphNodeExecutor(toolExecutor);
        ctx = OrchestrationContext.builder().executionId("exec-1").userId("u1").sessionId("s1").build();
        ctx.putVariable("city", "北京");
        ctx.putVariable("days", "3");
    }

    private GraphNode toolNode(Map<String, Object> config) {
        return GraphNode.builder()
                .nodeId("node-tool").type(NodeType.TOOL)
                .config(config).build();
    }

    @Test
    @DisplayName("TOOL 节点执行：参数模板渲染后经安全管线调用工具")
    void toolNodeExecutesWithRenderedArguments() {
        when(toolExecutor.execute(org.mockito.ArgumentMatchers.eq("weather"), anyString(), any(), any()))
                .thenReturn(ToolResult.builder().success(true).output("晴，25度").build());

        String output = nodeExecutor.executeToolNode(toolNode(Map.of(
                "toolName", "weather",
                "arguments", "{\"city\":\"${city}\",\"days\":${days}}")), ctx);

        assertThat(output).isEqualTo("晴，25度");
        // 产出写入节点 ID 同名变量
        assertThat(ctx.getVariable("node-tool")).isEqualTo("晴，25度");

        // 模板渲染验证：占位符被上下文变量替换
        ArgumentCaptor<String> argsCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(toolExecutor)
                .execute(org.mockito.ArgumentMatchers.eq("weather"), argsCaptor.capture(),
                        any(), any());
        assertThat(argsCaptor.getValue()).contains("\"city\":\"北京\"").contains("\"days\":3");
    }

    @Test
    @DisplayName("outputVar 指定产出变量名")
    void outputVarOverridesVariableName() {
        when(toolExecutor.execute(anyString(), anyString(), any(), any()))
                .thenReturn(ToolResult.builder().success(true).output("ok").build());

        nodeExecutor.executeToolNode(toolNode(Map.of(
                "toolName", "t", "arguments", "{}", "outputVar", "weatherResult")), ctx);

        assertThat(ctx.getVariable("weatherResult")).isEqualTo("ok");
        assertThat(ctx.getVariable("node-tool")).isNull();
    }

    @Test
    @DisplayName("缺省 arguments 为空对象；未知模板变量替换为空串")
    void defaultArgumentsAndUnknownVariable() {
        when(toolExecutor.execute(anyString(), anyString(), any(), any()))
                .thenReturn(ToolResult.builder().success(true).output("ok").build());

        nodeExecutor.executeToolNode(toolNode(Map.of("toolName", "t")), ctx);
        nodeExecutor.executeToolNode(toolNode(Map.of(
                "toolName", "t", "arguments", "{\"x\":\"${missing}\"}")), ctx);

        ArgumentCaptor<String> argsCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(toolExecutor, org.mockito.Mockito.times(2))
                .execute(anyString(), argsCaptor.capture(), any(), any());
        assertThat(argsCaptor.getAllValues().get(0)).isEqualTo("{}");
        assertThat(argsCaptor.getAllValues().get(1)).contains("\"x\":\"\"");
    }

    @Test
    @DisplayName("工具执行失败抛 NODE_TOOL_FAILED")
    void toolFailureThrows() {
        when(toolExecutor.execute(anyString(), anyString(), any(), isNull()))
                .thenReturn(ToolResult.builder().success(false).error("超时").build());

        assertThatThrownBy(() -> nodeExecutor.executeToolNode(toolNode(Map.of("toolName", "t")), ctx))
                .isInstanceOf(AgentEngineException.class)
                .hasMessageContaining("TOOL 节点执行失败")
                .hasMessageContaining("超时");
    }

    @Test
    @DisplayName("缺少 toolName 配置抛 NODE_CONFIG_INVALID")
    void missingToolNameThrows() {
        assertThatThrownBy(() -> nodeExecutor.executeToolNode(toolNode(Map.of()), ctx))
                .isInstanceOf(AgentEngineException.class)
                .hasMessageContaining("缺少 toolName");
        assertThatThrownBy(() -> nodeExecutor.executeToolNode(toolNode(null), ctx))
                .isInstanceOf(AgentEngineException.class);
    }

    @Test
    @DisplayName("无占位符模板原样返回；替换串含 $ 与 \\ 不破坏渲染")
    void templateEdgeCases() {
        ctx.putVariable("special", "a$100\\b");
        String rendered = nodeExecutor.renderTemplate(
                "prefix ${special} and ${city}", ctx);
        assertThat(rendered).isEqualTo("prefix a$100\\b and 北京");
        assertThat(nodeExecutor.renderTemplate("plain text", ctx)).isEqualTo("plain text");
        assertThat(nodeExecutor.renderTemplate(null, ctx)).isNull();
    }
}
