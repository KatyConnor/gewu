package com.gewu.interfaceapi.controller;

import com.gewu.application.agent.adapter.ChatUserInteractionGateway;
import com.gewu.application.session.ChatRunRegistry;
import com.gewu.application.session.MessageService;
import com.gewu.application.session.SessionService;
import com.gewu.application.session.dto.AskAnswerRequest;
import com.gewu.application.session.dto.CreateChatSessionCommand;
import com.gewu.application.session.dto.MessageDTO;
import com.gewu.application.session.dto.RunStatusDTO;
import com.gewu.application.session.dto.SessionDTO;
import com.gewu.common.dto.PageQuery;
import com.gewu.common.result.PageResult;
import com.gewu.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/ai/sessions")
@RequiredArgsConstructor
@Tag(name = "AI 对话会话", description = "AI 对话会话的创建、查询、消息历史与删除")
public class ChatSessionController {

    private final SessionService sessionService;
    private final MessageService messageService;
    private final ChatRunRegistry chatRunRegistry;
    private final ChatUserInteractionGateway chatUserInteractionGateway;

    @PostMapping
    @Operation(summary = "创建对话会话", description = "创建一个新的 AI 对话会话")
    public Result<SessionDTO> create(@Valid @RequestBody CreateChatSessionCommand command) {
        log.info("创建对话会话: agentId={}, title={}", command.getAgentId(), command.getTitle());
        return Result.success(sessionService.createChatSession(command));
    }

    @GetMapping
    @Operation(summary = "我的对话列表", description = "分页查询当前用户的对话会话列表")
    public Result<PageResult<SessionDTO>> mySessions(@Valid PageQuery query) {
        return Result.success(sessionService.listMySessions(query));
    }

    @GetMapping("/{sessionId}/messages")
    @Operation(summary = "会话消息历史", description = "分页查询指定会话的消息历史")
    public Result<PageResult<MessageDTO>> messages(@PathVariable String sessionId, @Valid PageQuery query,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "asc") String order) {
        return Result.success(messageService.listMessages(sessionId, query, order));
    }

    @GetMapping("/{sessionId}/run/status")
    @Operation(summary = "会话运行状态", description = "查询该会话是否有正在后台执行的聊天任务（断连不中断：前端据此轮询等待完成后刷新）；含挂起问时一并返回")
    public Result<RunStatusDTO> runStatus(@PathVariable String sessionId) {
        var pending = chatUserInteractionGateway.pendingBySession(sessionId);
        RunStatusDTO.PendingAskDTO pendingDto = pending == null ? null : RunStatusDTO.PendingAskDTO.builder()
                .askId(pending.askId())
                .question(pending.question())
                .options(pending.options())
                .build();
        return Result.success(RunStatusDTO.from(chatRunRegistry.get(sessionId), pendingDto));
    }

    @PostMapping("/{sessionId}/ask/{askId}/answer")
    @Operation(summary = "回答会话提问", description = "回复 ask_user 挂起的问题，恢复后台任务执行（answer 为空视为跳过）")
    public Result<Void> answerAsk(@PathVariable String sessionId, @PathVariable String askId,
                                  @RequestBody AskAnswerRequest request) {
        chatUserInteractionGateway.answerAsk(sessionId, askId, request.getAnswer());
        return Result.success();
    }

    @DeleteMapping("/{sessionId}")
    @Operation(summary = "删除对话会话", description = "删除指定对话会话及其消息")
    public Result<Void> delete(@PathVariable String sessionId) {
        log.info("删除对话会话: sessionId={}", sessionId);
        sessionService.deleteSession(sessionId);
        return Result.success();
    }
}