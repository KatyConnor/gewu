package com.gewu.interfaceapi.controller;

import com.gewu.application.session.MessageService;
import com.gewu.application.session.dto.*;
import com.gewu.common.dto.PageQuery;
import com.gewu.common.result.PageResult;
import com.gewu.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 消息接口 — 会话消息的发送、查询、编辑、删除与搜索.
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/messages")
@RequiredArgsConstructor
@Tag(name = "消息管理", description = "会话消息发送、查询、编辑与删除")
public class MessageController {

    private final MessageService messageService;

    @PostMapping
    @Operation(summary = "发送消息", description = "在指定会话中发送消息")
    public Result<MessageDTO> send(@PathVariable String sessionId, @Valid @RequestBody SendMessageCommand command) {
        return Result.success(messageService.sendMessage(sessionId, command));
    }

    @GetMapping
    @Operation(summary = "消息列表", description = "分页查询会话消息，按创建时间升序")
    public Result<PageResult<MessageDTO>> list(@PathVariable String sessionId, @Valid PageQuery query) {
        return Result.success(messageService.listMessages(sessionId, query));
    }

    @GetMapping("/search")
    @Operation(summary = "搜索消息", description = "在会话中按关键词搜索消息内容")
    public Result<PageResult<MessageDTO>> search(@PathVariable String sessionId, @RequestParam String keyword, @Valid PageQuery query) {
        return Result.success(messageService.searchMessages(sessionId, keyword, query));
    }

    @GetMapping("/{messageId}")
    @Operation(summary = "获取消息", description = "根据消息ID获取消息详情")
    public Result<MessageDTO> get(@PathVariable String sessionId, @PathVariable String messageId) {
        return Result.success(messageService.getMessage(messageId));
    }

    @PutMapping("/{messageId}")
    @Operation(summary = "编辑消息", description = "编辑消息内容，仅可编辑自己的消息")
    public Result<MessageDTO> edit(@PathVariable String sessionId, @PathVariable String messageId, @Valid @RequestBody EditMessageCommand command) {
        return Result.success(messageService.editMessage(messageId, command.getContent()));
    }

    @DeleteMapping("/{messageId}")
    @Operation(summary = "删除消息", description = "软删除消息，仅可删除自己的消息")
    public Result<Void> delete(@PathVariable String sessionId, @PathVariable String messageId) {
        messageService.deleteMessage(messageId);
        return Result.success();
    }
}
