package com.gewu.agent.engine.orchestration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handoff 指令解析器 - 从 Agent 输出中提取路由指令。
 * <p>Swarm 模式中，Agent 可在输出中嵌入 HANDOFF/FINISH 指令
 * 声明控制权移交或任务结束。
 * <p>支持的指令格式：
 * <ul>
 *   <li>{@code HANDOFF:agentId} - 移交控制权给指定 Agent</li>
 *   <li>{@code HANDOFF:agentId|reason} - 移交并附理由</li>
 *   <li>{@code FINISH} - 声明任务完成</li>
 *   <li>{@code FINISH:reason} - 声明完成并附理由</li>
 * </ul>
 *
 * @since 1.0.0
 */
@Slf4j
public class HandoffParser {

    private static final Pattern HANDOFF_PATTERN = Pattern.compile(
            "HANDOFF:([^|\\n\\s]+)(?:\\|([^\\n]*))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern FINISH_PATTERN = Pattern.compile(
            "FINISH(?::([^\\n]*))?", Pattern.CASE_INSENSITIVE);

    /**
     * 解析 Agent 输出中的路由指令。
     *
     * @param agentOutput Agent 的输出文本
     * @return 路由决策（targetNodeId 为 null 表示无指令，按默认路由）
     */
    public HandoffDecision parse(String agentOutput) {
        if (agentOutput == null || agentOutput.isBlank()) {
            return HandoffDecision.defaultRoute();
        }

        // 优先检测 FINISH
        Matcher finishMatcher = FINISH_PATTERN.matcher(agentOutput);
        if (finishMatcher.find()) {
            String reason = finishMatcher.group(1);
            log.debug("HandoffParser: FINISH detected, reason={}", reason);
            return HandoffDecision.finish(reason);
        }

        // 检测 HANDOFF
        Matcher handoffMatcher = HANDOFF_PATTERN.matcher(agentOutput);
        if (handoffMatcher.find()) {
            String target = handoffMatcher.group(1).trim();
            String reason = handoffMatcher.group(2);
            log.debug("HandoffParser: HANDOFF to={}, reason={}", target, reason);
            return HandoffDecision.handoff(target, reason);
        }

        return HandoffDecision.defaultRoute();
    }

    /**
     * 路由决策。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HandoffDecision {
        /** 目标节点 ID（null 表示默认路由） */
        private String targetNodeId;
        /** 是否结束 */
        private boolean finish;
        /** 决策理由 */
        private String reason;
        /** 是否检测到指令 */
        private boolean hasDirective;

        public static HandoffDecision handoff(String target, String reason) {
            return HandoffDecision.builder()
                    .targetNodeId(target)
                    .finish(false)
                    .reason(reason)
                    .hasDirective(true)
                    .build();
        }

        public static HandoffDecision finish(String reason) {
            return HandoffDecision.builder()
                    .targetNodeId(null)
                    .finish(true)
                    .reason(reason)
                    .hasDirective(true)
                    .build();
        }

        public static HandoffDecision defaultRoute() {
            return HandoffDecision.builder()
                    .targetNodeId(null)
                    .finish(false)
                    .hasDirective(false)
                    .build();
        }
    }
}