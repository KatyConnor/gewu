package com.veloflow.engine.trigger;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.veloflow.engine.definition.WorkflowInstanceService;
import com.veloflow.engine.persistence.mapper.WorkflowScheduleMapper;
import com.veloflow.engine.persistence.model.WorkflowSchedule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Veloflow 定时触发器（53 号 §3.1）：
 * 每分钟扫描到期启用配置；抢占即把 next_fire_at 推进到下次触发时间
 * （CAS 防多实例重复，进程崩溃只丢当次），并自愈 NULL 停摆行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowScheduleRunner {

    private final WorkflowScheduleMapper scheduleMapper;
    private final WorkflowInstanceService instanceService;

    @Scheduled(cron = "0 * * * * *")
    public void scanAndFire() {
        repairStuckSchedules();
        List<WorkflowSchedule> due = scheduleMapper.selectList(
                new LambdaQueryWrapper<WorkflowSchedule>()
                        .eq(WorkflowSchedule::getEnabled, 1)
                        .isNotNull(WorkflowSchedule::getNextFireAt)
                        .le(WorkflowSchedule::getNextFireAt, System.currentTimeMillis()));
        for (WorkflowSchedule schedule : due) {
            fire(schedule);
        }
    }

    /** 自愈：enabled=1 但 next_fire_at 为 NULL 的停摆行（进程崩溃/解析失败残留） */
    private void repairStuckSchedules() {
        List<WorkflowSchedule> stuck = scheduleMapper.selectList(
                new LambdaQueryWrapper<WorkflowSchedule>()
                        .eq(WorkflowSchedule::getEnabled, 1)
                        .isNull(WorkflowSchedule::getNextFireAt));
        for (WorkflowSchedule schedule : stuck) {
            Long next = WorkflowTriggerService.computeNextFireAt(schedule.getCronExpr(),
                    schedule.getTimezone(), System.currentTimeMillis());
            scheduleMapper.update(null, new LambdaUpdateWrapper<WorkflowSchedule>()
                    .eq(WorkflowSchedule::getId, schedule.getId())
                    .set(WorkflowSchedule::getNextFireAt, next));
            log.warn("自愈修复停摆的定时配置: scheduleId={}, nextFireAt={}", schedule.getId(), next);
        }
    }

    /** 抢占即推进到下次触发时间，再执行（进程崩溃只丢当次） */
    private void fire(WorkflowSchedule schedule) {
        Long expected = schedule.getNextFireAt();
        long now = System.currentTimeMillis();
        Long next = WorkflowTriggerService.computeNextFireAt(schedule.getCronExpr(),
                schedule.getTimezone(), now);
        int claimed = scheduleMapper.update(null, new LambdaUpdateWrapper<WorkflowSchedule>()
                .eq(WorkflowSchedule::getId, schedule.getId())
                .eq(WorkflowSchedule::getNextFireAt, expected)
                .set(WorkflowSchedule::getNextFireAt, next)
                .set(WorkflowSchedule::getLastFireAt, now));
        if (claimed == 0) {
            return;
        }
        log.info("定时触发流程: workflowId={}, scheduleId={}, nextFireAt={}",
                schedule.getWorkflowId(), schedule.getId(), next);
        try {
            instanceService.startByTrigger(schedule.getWorkflowId(), "system", null,
                    schedule.getInputTemplate() != null ? schedule.getInputTemplate() : "", "SCHEDULE");
        } catch (Exception e) {
            log.error("定时触发执行失败（调度不受影响，下次触发 {}）: workflowId={}", next,
                    schedule.getWorkflowId(), e);
        }
    }
}
