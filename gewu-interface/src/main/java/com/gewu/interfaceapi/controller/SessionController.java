package com.gewu.interfaceapi.controller;

import com.gewu.application.session.SessionService;
import com.gewu.application.session.dto.*;
import com.gewu.common.dto.PageQuery;
import com.gewu.common.result.PageResult;
import com.gewu.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 会话接口 — 会话的创建、查询、更新与删除.
 */
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
@Tag(name = "会话管理", description = "会话创建、查询、更新与删除")
public class SessionController {

    private final SessionService sessionService;

    @PostMapping
    @Operation(summary = "创建会话", description = "创建新的会话并添加创建者为管理员成员")
    public Result<SessionDTO> create(@Valid @RequestBody CreateSessionCommand command) {
        return Result.success(sessionService.createSession(command));
    }

    @GetMapping("/{sessionId}")
    @Operation(summary = "获取会话", description = "根据会话ID获取会话详情")
    public Result<SessionDTO> get(@PathVariable String sessionId) {
        return Result.success(sessionService.getSession(sessionId));
    }

    @GetMapping
    @Operation(summary = "会话列表", description = "分页查询所有会话")
    public Result<PageResult<SessionDTO>> list(@Valid PageQuery query) {
        return Result.success(sessionService.listSessions(query));
    }

    @GetMapping("/my")
    @Operation(summary = "我的会话", description = "分页查询当前用户参与的会话")
    public Result<PageResult<SessionDTO>> mySessions(@Valid PageQuery query) {
        return Result.success(sessionService.listMySessions(query));
    }

    @PutMapping("/{sessionId}")
    @Operation(summary = "更新会话", description = "更新会话信息，需为会话成员")
    public Result<SessionDTO> update(@PathVariable String sessionId, @Valid @RequestBody UpdateSessionCommand command) {
        return Result.success(sessionService.updateSession(sessionId, command));
    }

    @DeleteMapping("/{sessionId}")
    @Operation(summary = "删除会话", description = "软删除会话，需为会话成员")
    public Result<Void> delete(@PathVariable String sessionId) {
        sessionService.deleteSession(sessionId);
        return Result.success();
    }

    @GetMapping("/{sessionId}/members")
    @Operation(summary = "会话成员", description = "获取会话成员列表")
    public Result<List<SessionMemberDTO>> members(@PathVariable String sessionId) {
        return Result.success(sessionService.getSessionMembers(sessionId));
    }
}
