package com.veloflow.engine.trigger;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.veloflow.engine.commons.VeloflowErrorCode;
import com.veloflow.engine.commons.VeloflowException;
import com.veloflow.engine.commons.VlfId;
import com.veloflow.engine.commons.VlfSm3;
import com.veloflow.engine.definition.WorkflowInstanceService;
import com.veloflow.engine.persistence.mapper.WorkflowMapper;
import com.veloflow.engine.persistence.mapper.WorkflowScheduleMapper;
import com.veloflow.engine.persistence.mapper.WorkflowWebhookMapper;
import com.veloflow.engine.persistence.model.WorkflowSchedule;
import com.veloflow.engine.persistence.model.WorkflowWebhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;

/**
 * 触发配置服务（53 号 §3.1，51 号 §六）：定时（Cron）与 Webhook。
 * <p>Webhook token 明文仅创建/重置响应返回一次，库内只存 SM3 哈希；
 * 匿名触发按哈希索引命中，未命中/停用统一 404（不暴露存在性）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowTriggerService {

    private final WorkflowMapper workflowMapper;
    private final WorkflowScheduleMapper scheduleMapper;
    private final WorkflowWebhookMapper webhookMapper;
    private final WorkflowInstanceService instanceService;

    // ==================== 定时触发 ====================

    /** 保存定时触发配置（每流程一条，upsert）；保存即校验 Cron 并预计算下次触发时间 */
    @Transactional
    public WorkflowSchedule upsertSchedule(String workflowId, String cronExpr, String timezone,
                                           String inputTemplate, boolean enabled) {
        requireWorkflow(workflowId);
        if (cronExpr == null || cronExpr.isBlank()
                || !CronExpression.isValidExpression(cronExpr.trim())) {
            throw VeloflowException.of(VeloflowErrorCode.FLOW_VALIDATION_FAILED,
                    "Cron 表达式非法（Spring CronExpression 6 位，如 0 0 9 * * *）: " + cronExpr);
        }
        WorkflowSchedule entity = scheduleMapper.selectList(
                        new LambdaQueryWrapper<WorkflowSchedule>()
                                .eq(WorkflowSchedule::getWorkflowId, workflowId))
                .stream().findFirst().orElseGet(() -> {
                    WorkflowSchedule created = new WorkflowSchedule();
                    created.setId(VlfId.next());
                    created.setWorkflowId(workflowId);
                    return created;
                });
        entity.setCronExpr(cronExpr.trim());
        entity.setTimezone(timezone != null && !timezone.isBlank() ? timezone : "Asia/Shanghai");
        entity.setInputTemplate(inputTemplate);
        entity.setEnabled(enabled ? 1 : 0);
        entity.setNextFireAt(enabled
                ? computeNextFireAt(cronExpr.trim(), entity.getTimezone(), System.currentTimeMillis())
                : null);
        if (entity.getCreatedAt() == null) {
            scheduleMapper.insert(entity);
        } else {
            scheduleMapper.updateById(entity);
        }
        log.info("保存定时触发配置: workflowId={}, cron={}, enabled={}, nextFireAt={}",
                workflowId, cronExpr, enabled, entity.getNextFireAt());
        return entity;
    }

    /** 查询定时触发配置（未配置返回 null） */
    public WorkflowSchedule getSchedule(String workflowId) {
        return scheduleMapper.selectList(new LambdaQueryWrapper<WorkflowSchedule>()
                        .eq(WorkflowSchedule::getWorkflowId, workflowId))
                .stream().findFirst().orElse(null);
    }

    /** Cron 下次触发时间计算；非法返回 null */
    public static Long computeNextFireAt(String cronExpr, String timezone, long afterEpochMilli) {
        try {
            CronExpression cron = CronExpression.parse(cronExpr);
            ZoneId zone = ZoneId.of(timezone != null && !timezone.isBlank() ? timezone : "Asia/Shanghai");
            var next = cron.next(Instant.ofEpochMilli(afterEpochMilli).atZone(zone));
            return next != null ? next.toInstant().toEpochMilli() : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ==================== Webhook ====================

    /** Webhook 凭证（token 明文仅创建/重置响应返回一次） */
    public record WebhookCredential(String webhookId, String workflowId, boolean enabled, String token) {
    }

    /** 保存 Webhook 配置；首次或 regenerate 时生成新 token */
    @Transactional
    public WebhookCredential upsertWebhook(String workflowId, boolean enabled, boolean regenerate) {
        requireWorkflow(workflowId);
        WorkflowWebhook entity = webhookMapper.selectList(
                        new LambdaQueryWrapper<WorkflowWebhook>()
                                .eq(WorkflowWebhook::getWorkflowId, workflowId))
                .stream().findFirst().orElse(null);
        boolean isNew = entity == null;
        boolean generateToken = isNew || regenerate;
        String plainToken = generateToken ? generateWebhookToken() : null;
        if (isNew) {
            entity = new WorkflowWebhook();
            entity.setId(VlfId.next());
            entity.setWorkflowId(workflowId);
            entity.setTokenHash(hashToken(plainToken));
        } else if (generateToken) {
            entity.setTokenHash(hashToken(plainToken));
        }
        entity.setEnabled(enabled ? 1 : 0);
        if (isNew) {
            webhookMapper.insert(entity);
        } else {
            webhookMapper.updateById(entity);
        }
        log.info("保存 Webhook 配置: workflowId={}, enabled={}, regenerated={}", workflowId, enabled, generateToken);
        return new WebhookCredential(entity.getId(), workflowId, enabled, plainToken);
    }

    /** 查询 Webhook 配置（只含哈希不含明文；未配置返回 null） */
    public WorkflowWebhook getWebhook(String workflowId) {
        return webhookMapper.selectList(new LambdaQueryWrapper<WorkflowWebhook>()
                        .eq(WorkflowWebhook::getWorkflowId, workflowId))
                .stream().findFirst().orElse(null);
    }

    /**
     * Webhook 匿名触发：按 token SM3 哈希命中启用中的配置且流程为已发布才执行，
     * triggerType=WEBHOOK，发起人记为 webhook 便于审计。
     *
     * @return 实例 ID；未命中/停用/流程不可执行返回 null（端点统一 404）
     */
    public String triggerByWebhook(String token, String input) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String tokenHash = hashToken(token);
        WorkflowWebhook webhook = webhookMapper.selectList(
                        new LambdaQueryWrapper<WorkflowWebhook>()
                                .eq(WorkflowWebhook::getTokenHash, tokenHash))
                .stream().findFirst().orElse(null);
        if (webhook == null || webhook.getEnabled() == null || webhook.getEnabled() != 1) {
            return null;
        }
        var workflow = workflowMapper.selectById(webhook.getWorkflowId());
        if (workflow == null || workflow.getStatus() == null || workflow.getStatus() != 1) {
            return null;
        }
        log.info("Webhook 触发流程: workflowId={}, webhookId={}", webhook.getWorkflowId(), webhook.getId());
        return instanceService.startByTrigger(webhook.getWorkflowId(), "webhook", null,
                input != null ? input : "", "WEBHOOK").getInstanceId();
    }

    // ==================== 辅助 ====================

    private void requireWorkflow(String workflowId) {
        if (workflowMapper.selectById(workflowId) == null) {
            throw VeloflowException.of(VeloflowErrorCode.FLOW_NOT_FOUND);
        }
    }

    private String generateWebhookToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashToken(String token) {
        return VlfSm3.hashHex(token);
    }
}
