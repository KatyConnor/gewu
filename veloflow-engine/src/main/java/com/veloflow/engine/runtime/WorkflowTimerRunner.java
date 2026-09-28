package com.veloflow.engine.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.veloflow.engine.commons.VlfId;
import com.veloflow.engine.persistence.mapper.WorkflowNodeMapper;
import com.veloflow.engine.persistence.model.WorkflowNode;
import com.veloflow.engine.persistence.model.WorkflowNodeInstance;
import com.veloflow.engine.persistence.model.WorkflowNotification;
import com.veloflow.engine.persistence.mapper.WorkflowNotificationMapper;
import com.veloflow.engine.persistence.mapper.WorkflowNodeInstanceMapper;
import com.veloflow.engine.runtime.handler.TaskHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

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
    private final WorkflowNodeMapper nodeMapper;
    private final WorkflowNotificationMapper notificationMapper;
    private final com.veloflow.engine.persistence.mapper.WorkflowInstanceMapper instanceMapper;
    private final WorkflowScheduler scheduler;

    /** escalate 升级通知的额外接收人（管理员用户 ID，逗号分隔；宿主配置） */
    @Value("${veloflow.escalate.notify-userids:}")
    private String escalateNotifyUserIds;

    /**
     * 每分钟扫描到期等待行：
     * delay 节点 → 直接完成（timerFired）；
     * task/approval 节点 → 按 config.timeoutAction 驱动（51 号 §七）：
     *   approve 自动通过 / reject 自动驳回（触发回退原语）/ escalate 通知发起人并重设到期。
     */
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
            WorkflowNode node = nodeMapper.selectById(nodeInstance.getNodeId());
            String nodeType = node != null && node.getNodeType() != null ? node.getNodeType() : "";
            if ("task".equals(nodeType) || "approval".equals(nodeType)) {
                handleHumanTimeout(nodeInstance, node);
                continue;
            }
            log.info("定时器到期驱动节点完成: instanceId={}, nodeId={}",
                    nodeInstance.getInstanceId(), nodeInstance.getNodeId());
            scheduler.completeNodeExternally(nodeInstance.getInstanceId(), nodeInstance.getId(),
                    true, "{\"timerFired\": true}");
        }
    }

    /** 人工任务超时三动作（escalate 通知发起人并顺延；approve/reject 自动裁决） */
    private void handleHumanTimeout(WorkflowNodeInstance row, WorkflowNode node) {
        Map<String, Object> config = parseConfig(node != null ? node.getConfig() : null);
        String action = String.valueOf(config.getOrDefault("timeoutAction", "escalate"));
        String title = "任务超时提醒: " + row.getNodeName();
        switch (action) {
            case "approve" -> {
                log.info("任务超时自动通过: instanceId={}, nodeId={}", row.getInstanceId(), row.getNodeId());
                notify(row, "TIMEOUT_WARNING", "任务超时已自动通过", title);
                scheduler.completeNodeExternally(row.getInstanceId(), row.getId(),
                        true, "{\"approved\": true, \"timeout\": true}");
            }
            case "reject" -> {
                log.info("任务超时自动驳回: instanceId={}, nodeId={}", row.getInstanceId(), row.getNodeId());
                notify(row, "TIMEOUT_WARNING", "任务超时已自动驳回", title);
                scheduler.completeNodeExternally(row.getInstanceId(), row.getId(),
                        true, "{\"approved\": false, \"timeout\": true}");
            }
            default -> {
                // escalate：通知发起人与配置的管理员，顺延一个超时周期继续等待
                long hours = TaskHandler.parseLong(config.get("timeoutHours"), 24);
                String escalateContent = title + "（已超时，请尽快处理）";
                notify(row, "TIMEOUT_WARNING", "任务超时升级提醒", escalateContent);
                for (String adminId : configuredEscalateRecipients()) {
                    notifyUser(row, "ESCALATION", "任务超时升级：实例 " + row.getInstanceId()
                            + " 的 " + row.getNodeName() + " 已超时待处理", escalateContent, adminId);
                }
                nodeInstanceMapper.update(null, new LambdaUpdateWrapper<WorkflowNodeInstance>()
                        .eq(WorkflowNodeInstance::getId, row.getId())
                        .eq(WorkflowNodeInstance::getStatus, "running")
                        .set(WorkflowNodeInstance::getStatus, "waiting")
                        .set(WorkflowNodeInstance::getTimeoutAt,
                                System.currentTimeMillis() + hours * 3_600_000L));
            }
        }
    }

    /** 宿主配置的 escalate 管理员接收人（veloflow.escalate.notify-userids） */
    private List<String> configuredEscalateRecipients() {
        List<String> result = new java.util.ArrayList<>();
        if (escalateNotifyUserIds == null) {
            return result;
        }
        for (String id : escalateNotifyUserIds.split(",")) {
            if (!id.isBlank()) {
                result.add(id.trim());
            }
        }
        return result;
    }

    /** 超时通知（发起人，recipient=instance.initiatorId） */
    private void notify(WorkflowNodeInstance row, String type, String content, String title) {
        notifyUser(row, type, content, title, recipientOf(row.getInstanceId()));
    }

    /** 站内通知（指定接收人） */
    private void notifyUser(WorkflowNodeInstance row, String type, String content, String title, String recipientId) {
        WorkflowNotification n = new WorkflowNotification();
        n.setId(VlfId.next());
        n.setInstanceId(row.getInstanceId());
        n.setNodeInstanceId(row.getId());
        n.setType(type);
        n.setRecipientId(recipientId != null ? recipientId : "system");
        n.setTitle(title);
        n.setContent(content);
        n.setIsRead(0);
        n.setSentAt(System.currentTimeMillis());
        notificationMapper.insert(n);
    }

    private String recipientOf(String instanceId) {
        com.veloflow.engine.persistence.model.WorkflowInstance inst =
                instanceMapper.selectById(instanceId);
        return inst != null && inst.getInitiatorId() != null ? inst.getInitiatorId() : "system";
    }

    private Map<String, Object> parseConfig(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                    .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }
}
