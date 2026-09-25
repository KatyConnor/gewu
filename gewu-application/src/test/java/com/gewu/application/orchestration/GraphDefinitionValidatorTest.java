package com.gewu.application.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.orchestration.model.GraphEdge;
import com.gewu.agent.engine.orchestration.model.GraphNode;
import com.gewu.agent.engine.orchestration.model.NodeType;
import com.gewu.agent.engine.orchestration.model.OrchestrationGraph;
import com.gewu.agent.engine.orchestration.model.OrchestrationMode;
import com.gewu.domain.orchestration.OrchestrationGraphEntity;
import com.gewu.infrastructure.mapper.OrchestrationGraphMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 编排图结构校验器单测（VL 规则，docs/design/46 报告 §7.5）：
 * 覆盖 ERROR 级阻断规则与 WARNING 级提示规则；VL-13（WFO-04）覆盖跨图引用校验。
 */
@ExtendWith(MockitoExtension.class)
class GraphDefinitionValidatorTest {

    @Mock OrchestrationGraphMapper graphMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private GraphDefinitionValidator validator() {
        return new GraphDefinitionValidator(objectMapper, graphMapper);
    }

    private GraphNode node(String id, NodeType type) {
        return GraphNode.builder().nodeId(id).type(type).build();
    }

    private GraphEdge edge(String from, String to) {
        return GraphEdge.builder().fromNode(from).toNode(to).build();
    }

    private List<String> errors(List<GraphDefinitionValidator.ValidationIssue> issues) {
        return issues.stream().filter(GraphDefinitionValidator.ValidationIssue::isError)
                .map(GraphDefinitionValidator.ValidationIssue::ruleId).toList();
    }

    private List<String> allRules(List<GraphDefinitionValidator.ValidationIssue> issues) {
        return issues.stream().map(GraphDefinitionValidator.ValidationIssue::ruleId).toList();
    }

    @Test
    @DisplayName("合法 PIPELINE 线性图无任何问题")
    void validPipelineHasNoIssues() {
        OrchestrationGraph graph = OrchestrationGraph.builder()
                .mode(OrchestrationMode.PIPELINE)
                .nodes(List.of(node("n1", NodeType.AGENT), node("n2", NodeType.AGENT)))
                .edges(List.of(edge("n1", "n2")))
                .build();

        assertTrue(validator().validate(graph).isEmpty(), "合法图不应产生任何校验问题");
    }

    @Test
    @DisplayName("VL-01：nodeId 缺失或重复报 ERROR")
    void duplicateOrMissingNodeIdIsError() {
        OrchestrationGraph graph = OrchestrationGraph.builder()
                .mode(OrchestrationMode.PIPELINE)
                .nodes(List.of(node("n1", NodeType.AGENT), node("n1", NodeType.AGENT),
                        GraphNode.builder().type(NodeType.AGENT).build()))
                .edges(List.of())
                .build();

        assertEquals(List.of("VL-01", "VL-01"), errors(validator().validate(graph)));
    }

    @Test
    @DisplayName("VL-02：边引用不存在的节点报 ERROR")
    void danglingEdgeIsError() {
        OrchestrationGraph graph = OrchestrationGraph.builder()
                .mode(OrchestrationMode.PIPELINE)
                .nodes(List.of(node("n1", NodeType.AGENT)))
                .edges(List.of(edge("n1", "ghost")))
                .build();

        assertEquals(List.of("VL-02"), errors(validator().validate(graph)));
    }

    @Test
    @DisplayName("VL-03：PIPELINE 环形图报 ERROR")
    void cycleInPipelineIsError() {
        OrchestrationGraph graph = OrchestrationGraph.builder()
                .mode(OrchestrationMode.PIPELINE)
                .nodes(List.of(node("n1", NodeType.AGENT), node("n2", NodeType.AGENT)))
                .edges(List.of(edge("n1", "n2"), edge("n2", "n1")))
                .build();

        assertEquals(List.of("VL-03"), errors(validator().validate(graph)));
    }

    @Test
    @DisplayName("VL-03：ROUTER 出边缺 condition 报 ERROR")
    void routerEdgeWithoutConditionIsError() {
        OrchestrationGraph graph = OrchestrationGraph.builder()
                .mode(OrchestrationMode.PIPELINE)
                .nodes(List.of(node("n1", NodeType.AGENT), node("r1", NodeType.ROUTER),
                        node("n2", NodeType.AGENT)))
                .edges(List.of(edge("n1", "r1"), edge("r1", "n2")))
                .build();

        assertEquals(List.of("VL-03"), errors(validator().validate(graph)));
    }

    @Test
    @DisplayName("VL-04/VL-05：SWARM 与 SUPERVISOR 无 AGENT 节点报 ERROR")
    void swarmAndSupervisorWithoutAgentIsError() {
        OrchestrationGraph swarm = OrchestrationGraph.builder()
                .mode(OrchestrationMode.SWARM)
                .nodes(List.of(node("h1", NodeType.HUMAN)))
                .edges(List.of())
                .build();
        OrchestrationGraph supervisor = OrchestrationGraph.builder()
                .mode(OrchestrationMode.SUPERVISOR)
                .nodes(List.of(node("h1", NodeType.HUMAN)))
                .edges(List.of())
                .build();

        assertEquals(List.of("VL-04"), errors(validator().validate(swarm)));
        assertEquals(List.of("VL-05"), errors(validator().validate(supervisor)));
    }

    @Test
    @DisplayName("VL-06：TOOL 节点缺 config.toolName 报 ERROR")
    void toolNodeWithoutToolNameIsError() {
        OrchestrationGraph graph = OrchestrationGraph.builder()
                .mode(OrchestrationMode.PIPELINE)
                .nodes(List.of(node("t1", NodeType.TOOL)))
                .edges(List.of())
                .build();

        assertEquals(List.of("VL-06"), errors(validator().validate(graph)));
    }

    @Test
    @DisplayName("VL-12：SUPERVISOR 图带边仅 WARNING 不阻断")
    void supervisorWithEdgesIsWarningOnly() {
        OrchestrationGraph graph = OrchestrationGraph.builder()
                .mode(OrchestrationMode.SUPERVISOR)
                .nodes(List.of(node("s0", NodeType.AGENT), node("s1", NodeType.AGENT)))
                .edges(List.of(edge("s0", "s1")))
                .build();

        List<GraphDefinitionValidator.ValidationIssue> issues = validator().validate(graph);
        assertTrue(errors(issues).isEmpty(), "SUPERVISOR 带边不应有 ERROR 级问题");
        assertEquals(List.of("VL-12"), allRules(issues));
    }

    @Test
    @DisplayName("VL-08/VL-09：超时越界与未解析变量仅 WARNING")
    void timeoutAndVariableWarnings() {
        GraphNode human = GraphNode.builder()
                .nodeId("h1").type(NodeType.HUMAN)
                .config(Map.of("timeoutSeconds", 0))
                .inputs(Map.of("message", "${var.unknownVar}"))
                .build();
        OrchestrationGraph graph = OrchestrationGraph.builder()
                .mode(OrchestrationMode.PIPELINE)
                .nodes(List.of(human))
                .edges(List.of())
                .variables(Map.of("knownVar", "x"))
                .build();

        List<GraphDefinitionValidator.ValidationIssue> issues = validator().validate(graph);
        assertTrue(errors(issues).isEmpty(), "警告级规则不应产生 ERROR");
        assertEquals(List.of("VL-08", "VL-09"), allRules(issues));
    }

    @Test
    @DisplayName("VL-09：引用已声明图变量与前驱节点产出不告警")
    void resolvedVariableReferencesPass() {
        GraphNode agent = GraphNode.builder()
                .nodeId("a1").type(NodeType.AGENT)
                .inputs(Map.of("message", "${var.input}", "ref", "${var.topic}", "prev", "${var.n0}"))
                .build();
        OrchestrationGraph graph = OrchestrationGraph.builder()
                .mode(OrchestrationMode.PIPELINE)
                .nodes(List.of(node("n0", NodeType.AGENT), agent))
                .edges(List.of(edge("n0", "a1")))
                .variables(Map.of("topic", "x"))
                .build();

        assertTrue(validator().validate(graph).isEmpty(), "可解析的变量引用不应告警");
    }

    @Test
    @DisplayName("VL-09：无前缀 ${name} 写法同样纳入可解析性校验（WFO-06 对齐）")
    void plainVariableReferencesValidated() {
        GraphNode agent = GraphNode.builder()
                .nodeId("a1").type(NodeType.AGENT)
                .inputs(Map.of("ok", "${input}", "alsoOk", "${n0}", "bad", "${missingVar}"))
                .build();
        OrchestrationGraph graph = OrchestrationGraph.builder()
                .mode(OrchestrationMode.PIPELINE)
                .nodes(List.of(node("n0", NodeType.AGENT), agent))
                .edges(List.of(edge("n0", "a1")))
                .build();

        List<GraphDefinitionValidator.ValidationIssue> issues = validator().validate(graph);
        assertEquals(List.of("VL-09"), allRules(issues));
        assertEquals("a1", issues.get(0).nodeId());
    }

    @Test
    @DisplayName("VL-14：节点重试/超时配置越界仅 WARNING")
    void nodeRetryAndTimeoutBoundsAreWarning() {
        GraphNode agent = GraphNode.builder()
                .nodeId("a1").type(NodeType.AGENT)
                .config(Map.of("retryCount", 9, "timeoutSeconds", 100000))
                .build();
        OrchestrationGraph graph = OrchestrationGraph.builder()
                .mode(OrchestrationMode.PIPELINE)
                .nodes(List.of(agent))
                .edges(List.of())
                .build();

        List<GraphDefinitionValidator.ValidationIssue> issues = validator().validate(graph);
        assertTrue(errors(issues).isEmpty(), "VL-14 为 WARNING 级");
        assertEquals(List.of("VL-14", "VL-14"), allRules(issues));
    }

    @Test
    @DisplayName("VL-14：合法重试/超时配置不产生问题")
    void validNodeRetryAndTimeoutPass() {
        GraphNode agent = GraphNode.builder()
                .nodeId("a1").type(NodeType.AGENT)
                .config(Map.of("retryCount", 2, "retryBackoffMs", 500, "timeoutSeconds", 120))
                .build();
        OrchestrationGraph graph = OrchestrationGraph.builder()
                .mode(OrchestrationMode.PIPELINE)
                .nodes(List.of(agent))
                .edges(List.of())
                .build();

        assertTrue(validator().validate(graph).isEmpty(), "合法的重试/超时配置不应产生问题");
    }

    @Test
    @DisplayName("VL-13：SUBGRAPH 缺 refId 报 ERROR")
    void subgraphWithoutRefIdIsError() {
        GraphNode sub = GraphNode.builder().nodeId("sg1").type(NodeType.SUBGRAPH).build();
        OrchestrationGraph graph = OrchestrationGraph.builder()
                .graphId("g-a")
                .mode(OrchestrationMode.PIPELINE)
                .nodes(List.of(sub))
                .edges(List.of())
                .build();

        assertEquals(List.of("VL-13"), errors(validator().validate(graph)));
    }

    @Test
    @DisplayName("VL-13：SUBGRAPH 自引用报 ERROR")
    void subgraphSelfReferenceIsError() {
        GraphNode sub = GraphNode.builder().nodeId("sg1").type(NodeType.SUBGRAPH).refId("g-a").build();
        OrchestrationGraph graph = OrchestrationGraph.builder()
                .graphId("g-a")
                .mode(OrchestrationMode.PIPELINE)
                .nodes(List.of(sub))
                .edges(List.of())
                .build();

        assertEquals(List.of("VL-13"), errors(validator().validate(graph)));
    }

    @Test
    @DisplayName("VL-13：refId 指向 active 图且无环时通过")
    void subgraphToActiveGraphPasses() {
        GraphNode sub = GraphNode.builder().nodeId("sg1").type(NodeType.SUBGRAPH).refId("g-b").build();
        OrchestrationGraph graph = OrchestrationGraph.builder()
                .graphId("g-a")
                .mode(OrchestrationMode.PIPELINE)
                .nodes(List.of(sub))
                .edges(List.of())
                .build();
        OrchestrationGraphEntity target = new OrchestrationGraphEntity();
        target.setId("g-b");
        target.setStatus("active");
        target.setGraphDefinition("{\"mode\":\"PIPELINE\",\"nodes\":[{\"nodeId\":\"n1\",\"type\":\"AGENT\"}],\"edges\":[]}");
        when(graphMapper.selectById("g-b")).thenReturn(target);

        assertTrue(validator().validate(graph).isEmpty(), "指向 active 子图不应产生问题");
    }

    @Test
    @DisplayName("VL-13：refId 指向未激活图报 ERROR")
    void subgraphToInactiveGraphIsError() {
        GraphNode sub = GraphNode.builder().nodeId("sg1").type(NodeType.SUBGRAPH).refId("g-b").build();
        OrchestrationGraph graph = OrchestrationGraph.builder()
                .graphId("g-a")
                .mode(OrchestrationMode.PIPELINE)
                .nodes(List.of(sub))
                .edges(List.of())
                .build();
        OrchestrationGraphEntity target = new OrchestrationGraphEntity();
        target.setId("g-b");
        target.setStatus("draft");
        target.setGraphDefinition("{\"nodes\":[],\"edges\":[]}");
        when(graphMapper.selectById("g-b")).thenReturn(target);

        assertEquals(List.of("VL-13"), errors(validator().validate(graph)));
    }

    @Test
    @DisplayName("VL-13：A 引用 B、B 引用 A 的跨图环报 ERROR")
    void subgraphCrossGraphCycleIsError() {
        GraphNode subA = GraphNode.builder().nodeId("sg1").type(NodeType.SUBGRAPH).refId("g-b").build();
        OrchestrationGraph graphA = OrchestrationGraph.builder()
                .graphId("g-a")
                .mode(OrchestrationMode.PIPELINE)
                .nodes(List.of(subA))
                .edges(List.of())
                .build();
        OrchestrationGraphEntity graphB = new OrchestrationGraphEntity();
        graphB.setId("g-b");
        graphB.setStatus("active");
        graphB.setGraphDefinition("{\"mode\":\"PIPELINE\",\"nodes\":["
                + "{\"nodeId\":\"sg2\",\"type\":\"SUBGRAPH\",\"refId\":\"g-a\"}],\"edges\":[]}");
        when(graphMapper.selectById("g-b")).thenReturn(graphB);

        assertEquals(List.of("VL-13"), errors(validator().validate(graphA)));
    }

    @Test
    @DisplayName("画布坐标 x/y 随模型反序列化保留（设计器透传存储）")
    void canvasCoordinatesSurviveDeserialization() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        OrchestrationGraph graph = mapper.readValue("""
                {"mode":"PIPELINE","nodes":[{"nodeId":"n1","type":"AGENT","x":120.5,"y":80.0}],"edges":[]}
                """, OrchestrationGraph.class);

        assertEquals(120.5, graph.getNodes().get(0).getX());
        assertEquals(80.0, graph.getNodes().get(0).getY());
        assertTrue(validator().validate(graph).isEmpty());
    }
}
