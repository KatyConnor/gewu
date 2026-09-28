package com.gewu.application.config;

import com.gewu.agent.engine.core.AgentExecutor;
import com.gewu.agent.engine.core.AgentTask;
import com.gewu.agent.engine.llm.LlmClient;
import com.gewu.agent.engine.llm.LlmClientRegistry;
import com.gewu.agent.engine.llm.model.LlmRequest;
import com.gewu.agent.engine.llm.model.LlmResponse;
import com.gewu.agent.engine.llm.model.Message;
import com.gewu.application.orchestration.OrchestrationService;
import com.gewu.application.wenshi.search.WebSearchFragment;
import com.gewu.application.wenshi.search.WebSearchResult;
import com.gewu.application.wenshi.search.WebSearchService;
import com.gewu.domain.orchestration.OrchestrationExecutionEntity;
import com.veloflow.engine.ai.FlowAiBridge;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Veloflow AI 桥（51 号 §2.5/§九，P4）：FlowAiBridge SPI 的宿主实现——
 * llm 经 LlmClientRegistry 直调（编排同款客户端栈）、agent 经 AgentExecutor
 * （ReactAgentExecutor 全链路：安全/记忆/预算/路由）、orchestration 经
 * executeGraphForWorkflow（WORKFLOW_CALL，时长帽在编排引擎内约束）、
 * knowledge 经 Wenshi WebSearchService 检索适配（知识库模块落地后切换检索源）。
 * <p>AI 深度归编排轨，工作流只做调用与消费；编排侧不感知工作流内部结构。
 * <p>依赖经 ObjectProvider 可选注入（LlmClientRegistry/AgentExecutor 来自
 * agent-engine 自动配置，晚于组件扫描；@ConditionalOnBean 在普通组件上求值不可靠）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VeloflowAiBridge implements FlowAiBridge {

    private final ObjectProvider<LlmClientRegistry> llmClientRegistryProvider;
    private final ObjectProvider<AgentExecutor> agentExecutorProvider;
    private final ObjectProvider<OrchestrationService> orchestrationServiceProvider;
    private final ObjectProvider<WebSearchService> webSearchServiceProvider;

    @Override
    public LlmResult invokeLlm(LlmSpec spec) {
        LlmClientRegistry registry = llmClientRegistryProvider.getIfAvailable();
        if (registry == null) {
            throw new IllegalStateException("LLM 客户端注册中心不可用");
        }
        LlmClient client = registry.getClient(spec.modelProvider());
        Message message = Message.builder().role("user").content(spec.prompt()).build();
        LlmRequest.LlmRequestBuilder builder = LlmRequest.builder().messages(List.of(message));
        if (spec.modelName() != null && !spec.modelName().isBlank()) {
            builder.model(spec.modelName());
        }
        if (spec.temperature() != null) {
            builder.temperature(spec.temperature());
        }
        if (spec.maxTokens() != null) {
            builder.maxTokens(spec.maxTokens());
        }
        LlmResponse response = client.chat(builder.build());
        Integer totalTokens = response.getUsage() != null ? response.getUsage().getTotalTokens() : null;
        log.info("工作流 LLM 节点执行: provider={}, model={}, tokens={}",
                spec.modelProvider(), spec.modelName(), totalTokens);
        return new LlmResult(response.getContent(), totalTokens, spec.modelName());
    }

    @Override
    public AgentResult invokeAgent(AgentSpec spec) {
        AgentExecutor agentExecutor = agentExecutorProvider.getIfAvailable();
        if (agentExecutor == null) {
            throw new IllegalStateException("Agent 执行器不可用");
        }
        AgentTask task = AgentTask.builder()
                .agentId(spec.agentId())
                .message(spec.task())
                .sessionId(spec.workflowInstanceId())
                .userId("workflow")
                .build();
        LlmResponse response = agentExecutor.execute(task);
        Integer totalTokens = response.getUsage() != null ? response.getUsage().getTotalTokens() : null;
        log.info("工作流 agent 节点执行: agentId={}, instanceId={}, tokens={}",
                spec.agentId(), spec.workflowInstanceId(), totalTokens);
        return new AgentResult(response.getContent(), totalTokens);
    }

    @Override
    public OrchestrationResult invokeOrchestration(String graphId, String inputJson,
                                                   String workflowInstanceId) {
        OrchestrationService service = orchestrationServiceProvider.getIfAvailable();
        if (service == null) {
            throw new IllegalStateException("编排服务不可用");
        }
        OrchestrationExecutionEntity execution = service.executeGraphForWorkflow(
                graphId, workflowInstanceId, inputJson);
        String status = switch (execution.getStatus() == null ? "" : execution.getStatus()) {
            case "SUCCEEDED" -> "success";
            case "FAILED" -> "failed";
            default -> String.valueOf(execution.getStatus()).toLowerCase();
        };
        return new OrchestrationResult(status, execution.getId(),
                execution.getFinalOutput(), execution.getErrorMessage());
    }

    @Override
    public KnowledgeResult searchKnowledge(String knowledgeBaseId, String query, int topK) {
        WebSearchService searchService = webSearchServiceProvider.getIfAvailable();
        if (searchService == null) {
            throw new IllegalStateException("检索服务不可用");
        }
        WebSearchResult result = searchService.search(query);
        List<KnowledgeChunk> chunks = new ArrayList<>();
        if (result.getFragments() != null) {
            result.getFragments().stream()
                    .limit(Math.max(1, topK))
                    .forEach(f -> chunks.add(toChunk(f)));
        }
        String source = "wenshi-web-search: " + (knowledgeBaseId == null || knowledgeBaseId.isBlank()
                ? "default" : knowledgeBaseId)
                + (result.isSuccess() ? "" : "（检索未成功: " + result.getErrorMessage() + "）");
        return new KnowledgeResult(chunks, source);
    }

    private KnowledgeChunk toChunk(WebSearchFragment fragment) {
        return new KnowledgeChunk(fragment.getTitle(), fragment.getSnippet(), fragment.getUrl());
    }
}
