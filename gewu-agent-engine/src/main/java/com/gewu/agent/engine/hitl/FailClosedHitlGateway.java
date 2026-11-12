package com.gewu.agent.engine.hitl;

import reactor.core.publisher.Mono;

/**
 * fail-closed 审批网关（AE-P0-4）：审批请求一律拒绝而非自动批准。
 * <p>宿主未装配真实 {@link HitlGateway} 时由自动装配替换 {@link NoOpHitlGateway} 默认实现——
 * 29 号安全红线要求"生产变更/数据删除强制审批"，NoOp 直批会让该红线在无人察觉的情况下静默失效
 * （与 {@link NoOpSandboxExecutor} 的 fail-closed 语义对齐：安全类端口缺位即拒绝，其余端口缺位直通）。
 * 旧行为（直批）可用 {@code agent.engine.hitl.fail-closed=false} 关闭。
 *
 * @author 牛牛
 * @version 1.0.0
 * @since 2026-11-12
 */
public class FailClosedHitlGateway implements HitlGateway {

    @Override
    public Mono<HumanDecision> requestApproval(ApprovalRequest request) {
        return Mono.just(HumanDecision.builder()
                .decision("REJECTED")
                .operatorId("system")
                .value("fail-closed：未装配真实审批网关（agent.engine.adapter.enabled 未开启或未提供"
                        + " HitlGateway Bean），审批请求按拒绝处理")
                .build());
    }

    @Override
    public void submitDecision(String approvalId, HumanDecision decision) {
        // fail-closed：无真实审批存储，忽略提交
    }
}
