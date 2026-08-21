package com.gewu.agent.engine.orchestration.mode;

import com.gewu.agent.engine.core.AgentExecutor;
import com.gewu.agent.engine.core.event.AgentEvent;
import com.gewu.agent.engine.orchestration.model.GraphNode;
import com.gewu.agent.engine.orchestration.model.NodeType;
import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import com.gewu.agent.engine.orchestration.model.OrchestrationGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DebateModeHandler} 裁决事件测试（T1.3 修复验证）。
 * <p>原实现连续两次 .metadata() 调用，后者覆盖前者导致 graph_complete
 * 丢失 status/verdict；修复后三键合并输出。
 */
@DisplayName("Debate 模式裁决事件")
class DebateModeHandlerTest {

    @Test
    @DisplayName("graph_complete 事件 metadata 同时携带 status/verdict/output")
    void judgeVerdictMetadataComplete() {
        AgentExecutor executor = mock(AgentExecutor.class);
        when(executor.executeStream(any())).thenReturn(Flux.just(
                AgentEvent.builder().type("content").content("方案内容").build()));

        OrchestrationGraph graph = OrchestrationGraph.builder()
                .nodes(List.of(
                        GraphNode.builder().nodeId("debater-1").type(NodeType.AGENT)
                                .refId("agent-a").roleCode("ARCHITECT").build(),
                        GraphNode.builder().nodeId("debater-2").type(NodeType.AGENT)
                                .refId("agent-b").roleCode("DEVELOPER").build(),
                        GraphNode.builder().nodeId("judge").type(NodeType.MERGE)
                                .refId("judge-agent").roleCode("ARCHITECT").build()))
                .build();

        OrchestrationContext ctx = OrchestrationContext.builder()
                .executionId("exec-1").sessionId("sess-1").userId("user-1")
                .build();
        ctx.putVariable("input", "微服务还是单体？");

        List<AgentEvent> events = new DebateModeHandler(executor)
                .run(graph, ctx)
                .collectList().block();

        assertThat(events).isNotNull();
        AgentEvent complete = events.stream()
                .filter(e -> "graph_complete".equals(e.getType()))
                .findFirst().orElseThrow(() -> new AssertionError("缺少 graph_complete 事件"));

        assertThat(complete.getMetadata())
                .containsKeys("status", "verdict", "output")
                .containsEntry("status", "SUCCESS");
        assertThat(complete.getMetadata().get("verdict").toString()).contains("方案内容");
        assertThat(complete.getMetadata().get("output").toString()).contains("方案内容");
    }
}
