package com.gewu.application.orchestration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 定时触发配置测试（WFC-02）：Cron 下次触发时间计算与非法表达式兜底。
 */
class OrchestrationScheduleRunnerTest {

    @Test
    @DisplayName("合法 Cron 计算下次触发时间（晚于基准时间）")
    void computesNextFireTime() {
        long after = Instant.parse("2026-09-25T00:00:30Z").toEpochMilli();
        Long next = OrchestrationScheduleRunner.computeNextFireAt("0 * * * * *", "Asia/Shanghai", after);
        assertTrue(next != null && next > after, "下次触发时间应晚于基准");
    }

    @Test
    @DisplayName("非法 Cron 返回 null（调度暂停等待修正）")
    void invalidCronReturnsNull() {
        assertNull(OrchestrationScheduleRunner.computeNextFireAt("not-a-cron", "Asia/Shanghai", 0L));
    }

    @Test
    @DisplayName("空时区回退 Asia/Shanghai")
    void blankTimezoneFallsBack() {
        long after = Instant.parse("2026-09-25T00:00:00Z").toEpochMilli();
        Long next = OrchestrationScheduleRunner.computeNextFireAt("0 0 9 * * *", "", after);
        assertTrue(next != null && next > after);
    }
}
