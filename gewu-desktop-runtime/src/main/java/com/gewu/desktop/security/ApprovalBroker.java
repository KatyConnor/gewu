package com.gewu.desktop.security;

import com.gewu.desktop.config.DesktopProps;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 写授权中介（D1-21）：模型写文件 → 挂起等待用户 diff 确认 → 决策恢复。
 * <ul>
 *   <li>会话级 SSE emitter 注册（ChatController 流开始/结束时挂卸），approval_required
 *       事件与流式内容共享同一 SSE 通道（同步发送防交错）</li>
 *   <li>挂起实现：CompletableFuture + 超时（默认 5 分钟，超时视为拒绝）</li>
 *   <li>"本会话始终允许"：kv_store 记录 perm:{sessionId}:{tool}:always，后续同类写直接放行</li>
 * </ul>
 *
 * @since 1.0.0
 */
@Component
public class ApprovalBroker {

    private final JdbcTemplate jdbc;
    private final DesktopProps props;

    /** 挂起中的授权请求：approvalId → future */
    private final Map<String, PendingApproval> pending = new ConcurrentHashMap<>();
    /** 会话级流式 emitter（ChatController 注册） */
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    public ApprovalBroker(JdbcTemplate jdbc, DesktopProps props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    /** 授权等待上限(毫秒):可配置(默认 35 分钟)——大于前端挂起点(30 分钟),
     *  用户离开后回点「授权并继续」时决策仍能命中挂起中的 future,实现无缝恢复 */
    private long timeoutMs() {
        return Math.max(1, props.getApprovalTimeoutMinutes()) * 60 * 1000L;
    }

    public record PendingApproval(String approvalId, String sessionId, String tool,
                                  String path, String before, String after,
                                  CompletableFuture<Boolean> future) {
    }

    // ==================== emitter 注册（ChatController 调用） ====================

    public void registerEmitter(String sessionId, SseEmitter emitter) {
        emitters.put(sessionId, emitter);
    }

    public void unregisterEmitter(String sessionId) {
        emitters.remove(sessionId);
    }

    // ==================== 授权请求（LocalFileService 写路径调用，阻塞） ====================

    /** 发起授权并阻塞等待；true=允许。超时/通道不可用视为拒绝。 */
    public boolean request(String sessionId, String tool, String path, String before, String after) {
        // 视觉回归模式：自动授权（对话框已单独人工验证过）
        if (System.getenv("GEWU_VISUAL_TEST") != null) {
            return true;
        }
        // "本会话始终允许"白名单
        String alwaysKey = "perm:" + sessionId + ":" + tool + ":always";
        try {
            List<String> hit = jdbc.queryForList("SELECT v FROM kv_store WHERE k = ?", String.class, alwaysKey);
            if (!hit.isEmpty() && "1".equals(hit.get(0))) {
                return true;
            }
        } catch (Exception ignored) {
            // kv 查询失败按需走弹窗
        }
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter == null) {
            // 无活动流（如后台调用）：拒绝以防静默写入
            return false;
        }
        String approvalId = java.util.UUID.randomUUID().toString().replace("-", "");
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        PendingApproval rec = new PendingApproval(approvalId, sessionId, tool, path, before, after, future);
        pending.put(approvalId, rec);
        try {
            String payload = "{"
                    + "\"type\":\"approval_required\","
                    + "\"metadata\":{"
                    + "\"approvalId\":\"" + approvalId + "\","
                    + "\"tool\":\"" + escape(tool) + "\","
                    + "\"path\":\"" + escape(path) + "\","
                    + "\"before\":" + jsonStr(before) + ","
                    + "\"after\":" + jsonStr(after)
                    + "}}";
            synchronized (emitter) {
                emitter.send(SseEmitter.event().data(payload));
            }
            Boolean decided;
            try {
                decided = future.get(timeoutMs(), TimeUnit.MILLISECONDS);
                return Boolean.TRUE.equals(decided);
            } catch (java.util.concurrent.TimeoutException te) {
                // 超时:通知前端进入挂起态(前端可基于会话历史发起无感续跑),按拒绝收尾
                try {
                    synchronized (emitter) {
                        emitter.send(SseEmitter.event().data(
                                "{\"type\":\"approval_timeout\",\"metadata\":{\"approvalId\":\"" + approvalId + "\"}}"));
                    }
                } catch (Exception ignored) {
                    // 通道已断:前端经 decide 404 兜底
                }
                return false;
            } catch (Exception e) {
                return false; // 断连/中断 → 拒绝
            } finally {
                pending.remove(approvalId);
            }
        } catch (Exception e) {
            return false; // 通道不可用等 → 拒绝
        }
    }

    /** 决策（审批端点调用）；alwaysAllow=true 时写入会话级白名单 */
    public boolean decide(String approvalId, boolean approve, boolean alwaysAllow) {
        PendingApproval rec = pending.remove(approvalId);
        if (rec == null) {
            return false;
        }
        if (approve && alwaysAllow) {
            jdbc.update("MERGE INTO kv_store (k, v) KEY(k) VALUES (?, ?)",
                    "perm:" + rec.sessionId() + ":" + rec.tool() + ":always", "1");
        }
        rec.future().complete(approve);
        return true;
    }

    private static String jsonStr(String s) {
        return s == null ? "null" : "\"" + escape(s) + "\"";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
