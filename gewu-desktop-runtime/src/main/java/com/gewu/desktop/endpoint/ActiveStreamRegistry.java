package com.gewu.desktop.endpoint;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 活动流注册（执行中转向的判定依据）：chat/stream 引擎订阅建立时登记，
 * onComplete/onError 时注销——与 SseEmitter 连接状态无关（前端断开后引擎继续
 * 执行到收尾，期间插话仍应入队并被轮次边界消费）。
 *
 * @since 1.0.0
 */
@Component
public class ActiveStreamRegistry {

    private final Set<String> active = ConcurrentHashMap.newKeySet();

    public void register(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            active.add(sessionId);
        }
    }

    public void unregister(String sessionId) {
        if (sessionId != null) {
            active.remove(sessionId);
        }
    }

    public boolean isActive(String sessionId) {
        return sessionId != null && active.contains(sessionId);
    }
}
