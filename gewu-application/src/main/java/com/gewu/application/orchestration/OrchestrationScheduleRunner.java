package com.gewu.application.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.common.ulid.Ulid;
import com.gewu.domain.orchestration.OrchestrationScheduleEntity;
import com.gewu.infrastructure.mapper.OrchestrationScheduleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * 编排图定时触发器（WFC-02，EXEPLAN-ORCH-2026-09）。
 * <p>每分钟扫描到期的启用配置：CAS 抢占（UPDATE next_fire_at WHERE next_fire_at=旧值）
 * 防多实例重复触发；命中后以系统身份同步发起一次执行（trigger_type=SCHEDULE），
 * 失败仅告警不中断调度循环（下轮扫描按 next_fire_at 推进继续）。
 * <p>多实例说明：CAS 抢占保证同一行同一到期时间只被一个实例消费；
 * 进程重启不丢调度（状态在 DB，K8s 多副本前无需引入分布式调度器，见 48 号开放问题 4）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrchestrationScheduleRunner {

    private final OrchestrationScheduleMapper scheduleMapper;
    private final OrchestrationService orchestrationService;

    /** 每分钟扫描（对齐分钟级 cron 粒度） */
    @Scheduled(cron = "0 * * * * *")
    public void scanAndFire() {
        repairStuckSchedules();
        long now = Instant.now().toEpochMilli();
        List<OrchestrationScheduleEntity> due = scheduleMapper.selectList(
                new LambdaQueryWrapper<OrchestrationScheduleEntity>()
                        .eq(OrchestrationScheduleEntity::getEnabled, 1)
                        .isNotNull(OrchestrationScheduleEntity::getNextFireAt)
                        .le(OrchestrationScheduleEntity::getNextFireAt, now));
        for (OrchestrationScheduleEntity schedule : due) {
            fire(schedule, now);
        }
    }

    /**
     * 自愈（评审 F-02）：enabled=1 但 next_fire_at 为 NULL 的行（Cron 解析失败遗留、
     * 或旧版"置 NULL 锁"在进程崩溃窗口的残留）重算 next_fire_at，避免调度静默永久停摆。
     */
    private void repairStuckSchedules() {
        List<OrchestrationScheduleEntity> stuck = scheduleMapper.selectList(
                new LambdaQueryWrapper<OrchestrationScheduleEntity>()
                        .eq(OrchestrationScheduleEntity::getEnabled, 1)
                        .isNull(OrchestrationScheduleEntity::getNextFireAt));
        for (OrchestrationScheduleEntity schedule : stuck) {
            Long next = computeNextFireAt(schedule.getCronExpr(), schedule.getTimezone(),
                    Instant.now().toEpochMilli());
            scheduleMapper.update(null, new LambdaUpdateWrapper<OrchestrationScheduleEntity>()
                    .eq(OrchestrationScheduleEntity::getId, schedule.getId())
                    .set(OrchestrationScheduleEntity::getNextFireAt, next));
            log.warn("自愈修复停摆的定时配置: scheduleId={}, graphId={}, nextFireAt={}",
                    schedule.getId(), schedule.getGraphId(), next);
        }
    }

    /**
     * 单条触发（评审 F-02 修订）：抢占即把 next_fire_at **推进到下次触发时间**
     * （CAS where next_fire_at=期望值），再执行——执行中进程崩溃也只丢当次触发，
     * 调度随 next_fire_at 正常延续，不再依赖执行后的 finally 推进。
     */
    private void fire(OrchestrationScheduleEntity schedule, long now) {
        Long expectedFireAt = schedule.getNextFireAt();
        Long next = computeNextFireAt(schedule.getCronExpr(), schedule.getTimezone(), now);
        int claimed = scheduleMapper.update(null, new LambdaUpdateWrapper<OrchestrationScheduleEntity>()
                .eq(OrchestrationScheduleEntity::getId, schedule.getId())
                .eq(OrchestrationScheduleEntity::getNextFireAt, expectedFireAt)
                .set(OrchestrationScheduleEntity::getNextFireAt, next)
                .set(OrchestrationScheduleEntity::getLastFireAt, now));
        if (claimed == 0) {
            return; // 已被其他实例抢占
        }
        log.info("定时触发编排图: graphId={}, scheduleId={}, dueAt={}, nextFireAt={}",
                schedule.getGraphId(), schedule.getId(), expectedFireAt, next);
        try {
            orchestrationService.executeGraphInternal(schedule.getGraphId(), "system", null,
                    schedule.getInputTemplate() != null ? schedule.getInputTemplate() : "", "SCHEDULE");
        } catch (Exception e) {
            log.error("定时触发执行失败（调度循环不受影响，下次触发 {}）: graphId={}", next, schedule.getGraphId(), e);
        }
    }

    /** 计算下次触发时间；Cron 非法返回 null */
    public static Long computeNextFireAt(String cronExpr, String timezone, long afterEpochMilli) {
        try {
            CronExpression cron = CronExpression.parse(cronExpr);
            ZoneId zone = ZoneId.of(timezone != null && !timezone.isBlank() ? timezone : "Asia/Shanghai");
            ZonedDateTime after = Instant.ofEpochMilli(afterEpochMilli).atZone(zone);
            ZonedDateTime next = cron.next(after);
            return next != null ? next.toInstant().toEpochMilli() : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
