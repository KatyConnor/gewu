package com.gewu.application.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSE 连接管理器 — 按会话维度维护长连接并广播事件.
 */
@Slf4j
@Component
public class SseEventManager {

    private final ConcurrentHashMap<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public void addEmitter(String sessionId, SseEmitter emitter) {
        emitters.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(sessionId, emitter));
        emitter.onTimeout(() -> removeEmitter(sessionId, emitter));
        emitter.onError(e -> removeEmitter(sessionId, emitter));
    }

    public void removeEmitter(String sessionId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(sessionId);
        if (list == null) return;
        list.remove(emitter);
        if (list.isEmpty()) emitters.remove(sessionId, list);
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

    public void sendToUser(String userId, String eventName, Object data) {
        emitters.forEach((sessionId, list) -> sendEvent(sessionId, eventName, data));
    }
}