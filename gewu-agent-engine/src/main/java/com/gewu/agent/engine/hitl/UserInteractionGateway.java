package com.gewu.agent.engine.hitl;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * 用户交互网关 SPI（chat 链路 ask_user 内置工具的后端）——
 * agent 通过 ask_user 工具向用户提问并挂起等待回答，回答到达后继续执行。
 * <p>与 {@link HitlGateway}（编排审批）平行：本 SPI 面向聊天流式链路的轻量问答，
 * 实现方维护"挂起问"注册表（Sinks.One）与回答入口；框架提供
 * {@link NoOpUserInteractionGateway} 默认实现（立即返回提示，不挂起）。
 *
 * @since 1.0.0
 */
public interface UserInteractionGateway {

    /**
     * 向用户提问并挂起等待回答。
     * <p>返回 Mono 在用户回答（或跳过/超时降级应答）到达时完成，完成值即
     * 回灌给模型的工具结果文本。
     */
    Mono<String> askQuestion(AskRequest request);

    /** 提交用户回答（恢复挂起的 ask；文本原样作为工具结果回灌模型） */
    void answer(String askId, String answer);

    /** 提问答求数据 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class AskRequest {
        /** 本次提问的唯一 ID（事件透传给前端，回答端点据此恢复） */
        private String askId;
        private String sessionId;
        private String question;
        /** 可选项（≤4，用户可选中或自行输入） */
        private java.util.List<String> options;
        /** 挂起超时秒数；超时后以"未在限时内回答"应答降级续跑 */
        private int timeoutSeconds;
    }
}
