package com.gewu.application.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSE 连接管理器 - 按会话维度维护长连接并广播事件。
 * <p>双索引：会话维度（协作广播/HITL 审批）+ 用户维度（定向通知）。
 * 用户索引为连接注册时可选提供（T4.4 修复：sendToUser 原实现向
 * 全部会话连接广播，语义错误且无调用方，现按 userId 定向投递）。
 * <p>注意：连接表为进程内存，多副本部署需配合 S5 的 Redis Pub/Sub 改造。
 */
@Slf4j
@Component
public class SseEventManager {

    private final ConcurrentHashMap<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    /** 用户 -> 该用户建立的连接（跨其参与的多个会话） */
    private final ConcurrentHashMap<String, List<SseEmitter>> userEmitters = new ConcurrentHashMap<>();

    public void addEmitter(String sessionId, SseEmitter emitter) {
        addEmitter(sessionId, null, emitter);
    }

    /** 带用户身份注册：连接同时进入会话与用户两个索引 */
    public void addEmitter(String sessionId, String userId, SseEmitter emitter) {
        emitters.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        if (userId != null && !userId.isBlank()) {
            userEmitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        }
        emitter.onCompletion(() -> removeEmitter(sessionId, emitter));
        emitter.onTimeout(() -> removeEmitter(sessionId, emitter));
        emitter.onError(e -> removeEmitter(sessionId, emitter));
    }

    public void removeEmitter(String sessionId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(sessionId);
        if (list == null) return;
        list.remove(emitter);
        if (list.isEmpty()) emitters.remove(sessionId, list);
        // 同步清理用户索引（值为同一 emitter 实例）
        userEmitters.values().forEach(l -> l.remove(emitter));
    }

    public void sendEvent(String sessionId, String eventName, Object data) {
        List<SseEmitter> list = emitters.get(sessionId);
        if (list == null || list.isEmpty()) return;
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data, MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                log.warn("推送 SSE 事件失败 sessionId={}, 已移除失效连接", sessionId);
                removeEmitter(sessionId, emitter);
            }
        }
    }

    /** 向指定用户的全部连接定向推送（用户须注册时提供 userId） */
    public void sendToUser(String userId, String eventName, Object data) {
        List<SseEmitter> list = userEmitters.get(userId);
        if (list == null || list.isEmpty()) {
            log.debug("用户无活跃 SSE 连接，推送跳过: userId={}", userId);
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data, MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                log.warn("推送用户 SSE 事件失败 userId={}, 已移除失效连接", userId);
                list.remove(emitter);
            }
        }
    }

    /** 活跃会话连接数（监控/调试用） */
    public int activeSessionCount() {
        return emitters.size();
    }

    /** 活跃用户连接数（监控/调试用） */
    public int activeUserCount() {
        return userEmitters.size();
    }
}
