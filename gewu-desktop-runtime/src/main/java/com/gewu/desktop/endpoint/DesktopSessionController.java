package com.gewu.desktop.endpoint;

import com.gewu.desktop.adapter.DesktopSessionStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 引擎 API Profile：会话域（D1-12）。
 * 与平台 ChatSessionController 同构：/ai/sessions CRUD + 归档/置顶 + 消息历史，
 * 列表语义对齐 S9-M1（projectId 精确 / defaultSpace / 缺省排除已归档 / pinned 置顶优先）。
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/v1/ai/sessions")
public class DesktopSessionController {

    private final DesktopSessionStore store;
    private final com.gewu.desktop.adapter.DesktopProjectStore projectStore;
    private final com.gewu.agent.engine.hitl.TurnSteeringQueue turnSteeringQueue;
    private final ActiveStreamRegistry activeStreams;

    public DesktopSessionController(DesktopSessionStore store,
                                    com.gewu.desktop.adapter.DesktopProjectStore projectStore,
                                    com.gewu.agent.engine.hitl.TurnSteeringQueue turnSteeringQueue,
                                    ActiveStreamRegistry activeStreams) {
        this.store = store;
        this.projectStore = projectStore;
        this.turnSteeringQueue = turnSteeringQueue;
        this.activeStreams = activeStreams;
    }

    public record SteerCommand(String content, String clientId) {
    }

    /**
     * 执行中转向（用户插话补充指令，对齐平台 /v1/sessions/{sessionId}/steer）：
     * 用户消息即时落库 + 入队引擎转向队列，ReAct 循环在下一轮次边界以 user 角色
     * 并入上下文，与既有工具结果、原指令重组继续执行（不中断不重开）。
     * accepted=false 表示该会话当前没有活动执行流（前端应走正常发送链路）。
     */
    @org.springframework.web.bind.annotation.PostMapping("/{sessionId}/steer")
    public Map<String, Object> steer(@PathVariable String sessionId,
                                     @org.springframework.web.bind.annotation.RequestBody SteerCommand command) {
        if (!activeStreams.isActive(sessionId)) {
            return Map.of("accepted", false);
        }
        String content = command == null ? null : command.content();
        if (content == null || content.isBlank()) {
            return Map.of("accepted", false, "reason", "内容为空");
        }
        String clientId = command.clientId() == null || command.clientId().isBlank()
                ? null : command.clientId() + ":u";
        // 先落库（断连/降级均不丢），再入转向队列
        store.appendMessage(sessionId, "user", content, clientId, null);
        boolean queued = turnSteeringQueue.offer(sessionId, content);
        return Map.of("accepted", queued);
    }

    public record CreateSessionRequest(String title, String agentId, String projectId,
                                       String directory, String workspacePath) {
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody(required = false) CreateSessionRequest req) {
        String projectId = req == null ? null : req.projectId();
        String directory = req == null ? null : req.directory();
        if (projectId != null && !projectId.isBlank()) {
            // 项目必须存在；目录缺省继承项目绑定目录（项目=目录即工作区语义）
            Map<String, Object> project = projectStore.getProject(projectId);
            if (project == null) {
                throw new IllegalArgumentException("项目不存在: " + projectId);
            }
            if (directory == null || directory.isBlank()) {
                directory = String.valueOf(project.get("directory"));
            }
        }
        Map<String, Object> created = store.createSession(
                req == null ? null : req.title(),
                req == null ? null : req.agentId(),
                null, null,
                projectId,
                directory,
                req == null ? null : req.workspacePath());
        return store.toSessionDTO(created);
    }

    /** 列表（GET /my 同款过滤语义；status=2 查看已归档） */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String projectId,
                                          @RequestParam(required = false) Boolean defaultSpace,
                                          @RequestParam(required = false) Integer status) {
        return store.listSessions(projectId, defaultSpace, status).stream()
                .map(store::toSessionDTO).toList();
    }

    @GetMapping("/{sessionId}")
    public Map<String, Object> get(@PathVariable String sessionId) {
        Map<String, Object> row = store.getSession(sessionId);
        if (row == null) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }
        return store.toSessionDTO(row);
    }

    @GetMapping("/{sessionId}/messages")
    public List<Map<String, Object>> messages(@PathVariable String sessionId,
                                              @RequestParam(defaultValue = "200") int limit) {
        // 校验会话存在性
        if (store.getSession(sessionId) == null) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }
        return store.listMessages(sessionId, limit);
    }

    @PutMapping("/{sessionId}/archive")
    public Map<String, Object> archive(@PathVariable String sessionId) {
        store.archive(sessionId);
        return Map.of("ok", true);
    }

    @PutMapping("/{sessionId}/unarchive")
    public Map<String, Object> unarchive(@PathVariable String sessionId) {
        store.unarchive(sessionId);
        return Map.of("ok", true);
    }

    @PutMapping("/{sessionId}/pin")
    public Map<String, Object> pin(@PathVariable String sessionId,
                                   @RequestParam(defaultValue = "true") boolean pinned) {
        store.pin(sessionId, pinned);
        return Map.of("ok", true);
    }

    @DeleteMapping("/{sessionId}")
    public Map<String, Object> delete(@PathVariable String sessionId) {
        store.delete(sessionId);
        return Map.of("ok", true);
    }
}
