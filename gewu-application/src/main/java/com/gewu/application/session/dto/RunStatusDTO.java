package com.gewu.application.session.dto;

import com.gewu.application.session.ChatRunRegistry;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 会话运行状态 DTO（断连不中断修复）：前端重新进入会话时据此判断
 * 是否存在仍在后台执行的聊天任务，进行中则提示并轮询等待完成后刷新。
 */
@Getter
@Builder
public class RunStatusDTO {

    /** 是否有正在执行的运行轮次 */
    private final boolean active;

    /** 运行状态：RUNNING/DONE/FAILED；无记录时为 IDLE */
    private final String status;

    /** 本轮开始时间戳（毫秒） */
    private final long startedAt;

    /** 已运行时长（毫秒）；运行中为当前时刻减开始时刻 */
    private final long elapsedMs;

    /** 挂起问（ask_user 等待用户回答时返回，断连重进后据此恢复问答框） */
    private final PendingAskDTO pendingAsk;

    public static RunStatusDTO idle() {
        return RunStatusDTO.builder().active(false).status("IDLE").startedAt(0).elapsedMs(0).build();
    }

    public static RunStatusDTO from(ChatRunRegistry.RunMeta meta, PendingAskDTO pendingAsk) {
        if (meta == null) {
            return RunStatusDTO.builder().active(false).status("IDLE").startedAt(0).elapsedMs(0).pendingAsk(pendingAsk).build();
        }
        boolean active = meta.active();
        long now = System.currentTimeMillis();
        long elapsed = active ? now - meta.startedAt() : meta.finishedAt() - meta.startedAt();
        return RunStatusDTO.builder()
                .active(active)
                .status(meta.status().name())
                .startedAt(meta.startedAt())
                .elapsedMs(Math.max(elapsed, 0))
                .pendingAsk(pendingAsk)
                .build();
    }

    /** 挂起问 DTO（ask_user 等待用户回答） */
    @Getter
    @Builder
    public static class PendingAskDTO {
        private final String askId;
        private final String question;
        private final List<String> options;
    }
}
