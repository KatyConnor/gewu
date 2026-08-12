package com.gewu.application.agent;

import com.gewu.application.agent.dto.AgentExecutionRequest;
import com.gewu.domain.agent.Agent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Agent 上下文构建器 — 负责从 Agent 配置中解析执行上下文（沙箱配置等）.
 *
 * <p>职责：
 * <ul>
 *   <li>解析 Agent 的 modelConfig JSON 配置</li>
 *   <li>构建 ToolContext（包含沙箱启用状态、镜像等信息）</li>
 *   <li>处理配置解析异常</li>
 * </ul>
 */
@Slf4j
@Component
public class AgentContextBuilder {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 根据请求和 Agent 构建工具执行上下文。
     */
    public ToolContext buildToolContext(AgentExecutionRequest request, Agent agent) {
        boolean sandboxEnabled = false;
        String sandboxImage = null;
        
        if (agent != null && agent.getModelConfig() != null && !agent.getModelConfig().isBlank()) {
            try {
                JsonNode configNode = objectMapper.readTree(agent.getModelConfig());
                if (configNode.has("sandboxEnabled")) {
                    sandboxEnabled = configNode.get("sandboxEnabled").asBoolean(false);
                }
                if (configNode.has("sandboxImage")) {
                    sandboxImage = configNode.get("sandboxImage").asText(null);
                }
            } catch (Exception e) {
                log.warn("解析 Agent modelConfig 失败: agentId={}, config={}", 
                        agent.getId(), agent.getModelConfig(), e);
            }
        }

        return ToolContext.builder()
                .userId(request.getUserId())
                .sessionId(request.getSessionId())
                .agentId(request.getAgentId())
                .timeout(30)
                .sandboxEnabled(sandboxEnabled)
                .sandboxImage(sandboxImage)
                .build();
    }
}
