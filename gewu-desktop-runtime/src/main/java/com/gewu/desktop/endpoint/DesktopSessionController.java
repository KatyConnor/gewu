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

    public DesktopSessionController(DesktopSessionStore store) {
        this.store = store;
    }

    public record CreateSessionRequest(String title, String agentId, String projectId,
                                       String directory, String workspacePath) {
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody(required = false) CreateSessionRequest req) {
        Map<String, Object> created = store.createSession(
                req == null ? null : req.title(),
                req == null ? null : req.agentId(),
                null, null,
                req == null ? null : req.projectId(),
                req == null ? null : req.directory(),
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
