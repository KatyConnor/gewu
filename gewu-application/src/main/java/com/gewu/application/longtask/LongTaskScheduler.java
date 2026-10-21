package com.gewu.application.longtask;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 长任务调度器（docs/design/45 §5）：领取到期任务，逐个执行一个"段"。
 * <p>Spring 默认单线程调度器 → 串行执行（天然互斥）；段内超时由
 * segment-timeout-seconds + ReactAgentExecutor 预算体系兜底。
 * 崩溃恢复：段 running 心跳超时 → runSegment 起始处标记中断 → 检查点续跑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LongTaskScheduler {

    private final LongTaskService longTaskService;

    @Scheduled(fixedDelay = 30_000)
    public void tick() {
        try {
            longTaskService.runDueSegments();
        } catch (Exception e) {
            log.error("长任务调度轮询异常: {}", e.getMessage(), e);
        }
    }
}
