package com.gewu.application.workflow.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.gewu.domain.workflow.WorkflowNodeInstance;
import com.gewu.infrastructure.mapper.WorkflowNodeInstanceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工作流定时器扫描器（51 号 §四推进事件源③）。
 * <p>每分钟扫描到期的等待型节点（timeout_at <= now 且 status=waiting），
 * CAS 抢占（waiting→running 原子更新）后驱动完成——延时节点由此推进；
 * 进程重启不丢延时（状态在 DB）。审批超时治理（P2）复用本扫描器扩展 timeoutAction。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowTimerRunner {

    private final WorkflowNodeInstanceMapper nodeInstanceMapper;
    private final WorkflowScheduler scheduler;

    @Scheduled(cron = "0 * * * * *")
    public void scanExpiredWaits() {
        List<WorkflowNodeInstance> expired = nodeInstanceMapper.selectList(
                new LambdaQueryWrapper<WorkflowNodeInstance>()
                        .eq(WorkflowNodeInstance::getStatus, "waiting")
                        .isNotNull(WorkflowNodeInstance::getTimeoutAt)
                        .le(WorkflowNodeInstance::getTimeoutAt, System.currentTimeMillis())
                        .last("LIMIT 100"));
        for (WorkflowNodeInstance nodeInstance : expired) {
            // CAS 抢占：仅 waiting 态可被定时器驱动（人工/其他路径已完成的行不受影响）
            int claimed = nodeInstanceMapper.update(null, new LambdaUpdateWrapper<WorkflowNodeInstance>()
                    .eq(WorkflowNodeInstance::getId, nodeInstance.getId())
                    .eq(WorkflowNodeInstance::getStatus, "waiting")
                    .set(WorkflowNodeInstance::getStatus, "running"));
            if (claimed == 0) {
                continue;
            }
            log.info("定时器到期驱动节点完成: instanceId={}, nodeId={}",
                    nodeInstance.getInstanceId(), nodeInstance.getNodeId());
            scheduler.completeNodeExternally(nodeInstance.getInstanceId(), nodeInstance.getId(),
                    true, "{\"timerFired\": true}");
        }
    }
}
