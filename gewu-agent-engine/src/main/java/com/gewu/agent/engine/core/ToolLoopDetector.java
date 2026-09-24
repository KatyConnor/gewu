package com.gewu.agent.engine.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * 工具调用死循环检测器 - 识别"同工具 + 相同参数"的连续重复调用（借鉴 Gemini CLI 的
 * loop detection / OpenCode 的 doom_loop 思路）。
 * <p>每次执行创建一个实例，跨轮次维护状态；签名 = 工具名 + 键序归一化后的参数 JSON
 * （键序不同、语义相同的调用视为同一次调用）。分级响应由调用方执行：
 * 连续次数达到告警阈值时注入策略提示（nudge），达到终止阈值时强制收尾。
 */
final class ToolLoopDetector {

    /** 参数规范化用（排序键序），独立实例避免污染全局 ObjectMapper 配置 */
    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private String lastSignature;
    private int consecutive;
    private boolean nudged;

    /**
     * 记录一次工具调用，返回记录后的"连续相同调用"次数。
     * 与上一次签名不同时计数归 1（重新起算）。
     */
    synchronized int record(String toolName, String arguments) {
        String signature = toolName + "#" + normalize(arguments);
        if (signature.equals(lastSignature)) {
            consecutive++;
        } else {
            lastSignature = signature;
            consecutive = 1;
        }
        return consecutive;
    }

    /** 是否已注入过策略提示（nudge 全程只注入一次，避免刷屏） */
    synchronized boolean isNudged() {
        return nudged;
    }

    synchronized void markNudged() {
        this.nudged = true;
    }

    /** 供测试断言的当前连续计数 */
    synchronized int consecutiveCount() {
        return consecutive;
    }

    /** 参数归一化：JSON 键序排序后序列化；解析失败时使用原文（比对退化为精确匹配） */
    private static String normalize(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return "";
        }
        try {
            Object parsed = CANONICAL_MAPPER.readValue(arguments, Object.class);
            return CANONICAL_MAPPER.writeValueAsString(parsed);
        } catch (Exception e) {
            return arguments;
        }
    }
}
