package com.gewu.agent.engine.hitl;

import reactor.core.publisher.Mono;

/**
 * {@link UserInteractionGateway} 的 NoOp 默认实现 - 不挂起，立即以提示文本应答。
 * <p>适用于未装配真实交互通道（如无前端问答界面）的场景，保证 ask_user
 * 工具在无网关时仍能完成而不阻塞执行。
 *
 * @since 1.0.0
 */
public class NoOpUserInteractionGateway implements UserInteractionGateway {

    @Override
    public Mono<String> askQuestion(AskRequest request) {
        return Mono.just("（当前未启用用户交互通道，请基于已有信息自主决策并继续任务）");
    }

    @Override
    public void answer(String askId, String answer) {
        // NoOp
    }
}
