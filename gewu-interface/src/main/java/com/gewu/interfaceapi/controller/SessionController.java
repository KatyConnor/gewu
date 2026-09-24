package com.gewu.interfaceapi.controller;

import com.gewu.application.agent.adapter.ChatUserInteractionGateway;
import com.gewu.application.session.ChatRunRegistry;
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
    private final ChatRunRegistry chatRunRegistry;
    private final ChatUserInteractionGateway chatUserInteractionGateway;

    // ==================== 运行时状态（断连不中断 / HITL 问答 / 实时过程） ====================
    // 注意：本控制器是前端 lib/session.ts 的路由家族（/api/v1/sessions）。
    // 运行时端点必须注册在此家族下——此前误注册于 /api/v1/ai/sessions，前端经代理
    // 调用 404，导致重进会话无横幅/无时间线/无问答框（checkRunStatus 静默 catch）。

    /** 会话当前运行状态（active/挂起问），供重进会话恢复横幅、轮询与问答框 */
    @GetMapping("/{sessionId}/run/status")
    @Operation(summary = "会话运行状态", description = "查询该会话是否有正在后台执行的聊天任务；含挂起问时一并返回")
    public Result<RunStatusDTO> runStatus(@PathVariable String sessionId) {
        var pending = chatUserInteractionGateway.pendingBySession(sessionId);
        RunStatusDTO.PendingAskDTO pendingDto = pending == null ? null : RunStatusDTO.PendingAskDTO.builder()
                .askId(pending.askId())
                .question(pending.question())
                .options(pending.options())
                .build();
        return Result.success(RunStatusDTO.from(chatRunRegistry.get(sessionId), pendingDto));
    }

    /** 执行中任务的实时过程时间线条目（与消息 metadata.process 同构；无活动 run 时 items 为空） */
    @GetMapping("/{sessionId}/run/process")
    @Operation(summary = "会话运行实时过程", description = "读取执行中任务的实时过程时间线（重进会话后轮询渲染）")
    public Result<RunProcessDTO> runProcess(@PathVariable String sessionId) {
        var meta = chatRunRegistry.get(sessionId);
        long startedAt = meta != null ? meta.startedAt() : 0L;
        java.util.List<java.util.Map<String, Object>> items = chatRunRegistry.pollLiveProcess(sessionId);
        return Result.success(RunProcessDTO.builder()
                .items(items == null ? java.util.List.of() : items)
                .startedAt(startedAt)
                .build());
    }

    /** 用户回答（ask_user 挂起问）：恢复后台任务执行（answer 为空视为跳过） */
    @PostMapping("/{sessionId}/ask/{askId}/answer")
    @Operation(summary = "回答会话提问", description = "回复 ask_user 挂起的问题，恢复后台任务执行（answer 为空视为跳过）")
    public Result<Void> answerAsk(@PathVariable String sessionId, @PathVariable String askId,
                                  @RequestBody AskAnswerRequest request) {
        chatUserInteractionGateway.answerAsk(sessionId, askId, request.getAnswer());
        return Result.success();
    }

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
    @Operation(summary = "我的会话", description = "分页查询当前用户参与的会话（支持项目/默认空间/归档过滤）")
    public Result<PageResult<SessionDTO>> mySessions(
            @Valid PageQuery query,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false, defaultValue = "false") boolean defaultSpace,
            @RequestParam(required = false) Integer status) {
        return Result.success(sessionService.listMySessions(query, projectId, defaultSpace, status));
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

    // ==================== 会话增值生命周期（T3.3） ====================

    @PutMapping("/{sessionId}/archive")
    @Operation(summary = "归档会话", description = "状态置为已归档并记录归档时间，需为会话成员")
    public Result<SessionDTO> archive(@PathVariable String sessionId) {
        return Result.success(sessionService.archiveSession(sessionId));
    }

    @PutMapping("/{sessionId}/unarchive")
    @Operation(summary = "取消归档", description = "恢复为进行中状态，仅已归档会话可取消")
    public Result<SessionDTO> unarchive(@PathVariable String sessionId) {
        return Result.success(sessionService.unarchiveSession(sessionId));
    }

    @PostMapping("/{sessionId}/share")
    @Operation(summary = "开启分享", description = "生成短 slug 并公开会话；幂等（已有 slug 复用）")
    public Result<SessionDTO> share(@PathVariable String sessionId) {
        return Result.success(sessionService.shareSession(sessionId));
    }

    @DeleteMapping("/{sessionId}/share")
    @Operation(summary = "取消分享", description = "关闭公开并清空分享链接")
    public Result<SessionDTO> unshare(@PathVariable String sessionId) {
        return Result.success(sessionService.unshareSession(sessionId));
    }

    @PutMapping("/{sessionId}/pin")
    @Operation(summary = "置顶会话", description = "会话列表置顶优先展示")
    public Result<SessionDTO> pin(@PathVariable String sessionId) {
        return Result.success(sessionService.pinSession(sessionId, true));
    }

    @DeleteMapping("/{sessionId}/pin")
    @Operation(summary = "取消置顶", description = "取消会话置顶")
    public Result<SessionDTO> unpin(@PathVariable String sessionId) {
        return Result.success(sessionService.pinSession(sessionId, false));
    }
}
