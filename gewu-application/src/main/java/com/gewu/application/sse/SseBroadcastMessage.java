package com.gewu.application.sse;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * SSE 跨实例广播消息（T5.1 多副本改造）。
 * <p>经 Redis 频道 {@code gewu:sse:broadcast} 传递；接收实例按 origin 跳过
 * 自发消息（本地已直发），按 scope 路由到会话/用户/HITL 处理器。
 *
 * @param origin 发布实例 ID（防自环）
 * @param scope  路由范围：session / user / hitl
 * @param target 路由键（sessionId / userId / approvalId）
 * @param eventName SSE 事件名（hitl 范围忽略）
 * @param payload 载荷 JSON 字符串
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SseBroadcastMessage(
        String origin,
        String scope,
        String target,
        String eventName,
        String payload) {

    public static final String SCOPE_SESSION = "session";
    public static final String SCOPE_USER = "user";
    public static final String SCOPE_HITL = "hitl";
}
