package com.gewu.application.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.application.agent.dto.AgentChunk;
import com.gewu.application.agent.dto.AgentExecutionRequest;
import com.gewu.application.agent.dto.ToolResult;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.agent.Agent;
import com.gewu.domain.agent.AgentTool;
import com.gewu.domain.session.SessionMessage;
import com.gewu.infrastructure.llm.*;
import com.gewu.infrastructure.mapper.AgentMapper;
import com.gewu.infrastructure.mapper.AgentToolMapper;
import com.gewu.infrastructure.mapper.SessionMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentExecutionEngine {

    private static final int MAX_TOOL_ROUNDS = 10;
    private static final int DEFAULT_HISTORY_LIMIT = 50;

    private final LlmClientFactory llmClientFactory;
    private final ToolExecutionService toolExecutionService;
    private final AgentMapper agentMapper;
    private final AgentToolMapper agentToolMapper;
    private final SessionMessageMapper sessionMessageMapper;

    public LlmResponse executeAgent(AgentExecutionRequest request) {
        Agent agent = loadAgent(request.getAgentId());
        LlmClient client = llmClientFactory.getClient(agent.getModelProvider());
        List<Message> messages = buildMessages(agent, request);
        List<ToolDefinition> tools = buildToolDefinitions(request.getAgentId());
        ToolContext toolContext = buildToolContext(request);

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            LlmRequest llmRequest = LlmRequest.builder()
                    .model(agent.getModelName())
                    .messages(messages)
                    .tools(tools.isEmpty() ? null : tools)
                    .temperature(0.7)
                    .maxTokens(4096)
                    .stream(false)
                    .build();

            LlmResponse response = client.chat(llmRequest);

            if (response.getToolCalls() == null || response.getToolCalls().isEmpty()) {
                return response;
            }

            messages.add(Message.builder()
                    .role("assistant")
                    .content(response.getContent())
                    .build());

            for (ToolCall toolCall : response.getToolCalls()) {
                ToolResult result = toolExecutionService.executeTool(
                        toolCall.getName(), toolCall.getArguments(), toolContext);
                String output = result.isSuccess() ? result.getOutput() : result.getError();
                messages.add(Message.builder()
                        .role("tool")
                        .content(output)
                        .toolCallId(toolCall.getId())
                        .name(toolCall.getName())
                        .build());
            }
        }

        throw BusinessException.of(ResultCode.AGENT_EXECUTION_FAILED, "工具调用轮次超限");
    }

    public Flux<AgentChunk> executeAgentStream(AgentExecutionRequest request) {
        Agent agent = loadAgent(request.getAgentId());
        LlmClient client = llmClientFactory.getClient(agent.getModelProvider());
        List<Message> messages = buildMessages(agent, request);
        List<ToolDefinition> tools = buildToolDefinitions(request.getAgentId());

        return Flux.defer(() -> streamRound(client, agent.getModelName(), messages, tools, request, 0));
    }

    private Flux<AgentChunk> streamRound(LlmClient client, String model, List<Message> messages,
                                         List<ToolDefinition> tools, AgentExecutionRequest request, int round) {
        if (round >= MAX_TOOL_ROUNDS) {
            return Flux.just(AgentChunk.builder()
                    .type("error")
                    .errorMessage("工具调用轮次超限")
                    .build());
        }

        LlmRequest llmRequest = LlmRequest.builder()
                .model(model)
                .messages(messages)
                .tools(tools.isEmpty() ? null : tools)
                .temperature(0.7)
                .maxTokens(4096)
                .stream(true)
                .build();

        return client.chatStream(llmRequest)
                .map(chunk -> {
                    if (chunk.getDelta() != null && !chunk.getDelta().isEmpty()) {
                        return AgentChunk.builder()
                                .type("content")
                                .content(chunk.getDelta())
                                .build();
                    }
                    if (chunk.getToolCallDelta() != null) {
                        LlmChunk.ToolCallDelta tcd = chunk.getToolCallDelta();
                        return AgentChunk.builder()
                                .type("tool_call")
                                .toolCall(AgentChunk.ToolCallInfo.builder()
                                        .id(tcd.getId())
                                        .name(tcd.getName())
                                        .arguments(tcd.getArguments())
                                        .build())
                                .build();
                    }
                    return AgentChunk.builder().type("content").content("").build();
                })
                .concatWith(Flux.defer(() -> {
                    messages.add(Message.builder()
                            .role("assistant")
                            .content("")
                            .build());
                    return Flux.just(AgentChunk.builder().type("done").build());
                }));
    }

    private Agent loadAgent(String agentId) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw BusinessException.of(ResultCode.AGENT_NOT_FOUND);
        }
        return agent;
    }

    private List<Message> buildMessages(Agent agent, AgentExecutionRequest request) {
        List<Message> messages = new ArrayList<>();

        if (agent.getSystemPrompt() != null && !agent.getSystemPrompt().isEmpty()) {
            messages.add(Message.builder()
                    .role("system")
                    .content(agent.getSystemPrompt())
                    .build());
        }

        if (request.getHistory() != null) {
            messages.addAll(request.getHistory());
        } else if (request.getSessionId() != null) {
            List<SessionMessage> sessionMessages = sessionMessageMapper.selectList(
                    new LambdaQueryWrapper<SessionMessage>()
                            .eq(SessionMessage::getSessionId, request.getSessionId())
                            .orderByAsc(SessionMessage::getSeq)
                            .last("LIMIT " + DEFAULT_HISTORY_LIMIT));

            for (SessionMessage sm : sessionMessages) {
                String role = "user".equals(sm.getMessageType()) ? "user" : "assistant";
                messages.add(Message.builder()
                        .role(role)
                        .content(sm.getContent())
                        .build());
            }
        }

        if (request.getMessage() != null && !request.getMessage().isEmpty()) {
            messages.add(Message.builder()
                    .role("user")
                    .content(request.getMessage())
                    .build());
        }

        return messages;
    }

    private List<ToolDefinition> buildToolDefinitions(String agentId) {
        List<AgentTool> tools = agentToolMapper.selectList(
                new LambdaQueryWrapper<AgentTool>()
                        .eq(AgentTool::getAgentId, agentId)
                        .eq(AgentTool::getStatus, 1)
                        .orderByAsc(AgentTool::getSortOrder));

        return tools.stream()
                .map(tool -> ToolDefinition.builder()
                        .name(tool.getToolName())
                        .description(tool.getDescription())
                        .parameters(tool.getRequestSchema())
                        .build())
                .toList();
    }

    private ToolContext buildToolContext(AgentExecutionRequest request) {
        return ToolContext.builder()
                .userId(request.getUserId())
                .sessionId(request.getSessionId())
                .agentId(request.getAgentId())
                .timeout(30)
                .sandboxEnabled(false)
                .build();
    }
}
