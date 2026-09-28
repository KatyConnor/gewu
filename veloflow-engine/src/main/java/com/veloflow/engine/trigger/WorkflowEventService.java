package com.veloflow.engine.trigger;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.veloflow.engine.commons.VeloflowJson;
import com.veloflow.engine.definition.WorkflowInstanceService;
import com.veloflow.engine.persistence.mapper.WorkflowEventSubscriptionMapper;
import com.veloflow.engine.persistence.mapper.WorkflowMapper;
import com.veloflow.engine.persistence.mapper.WorkflowNodeMapper;
import com.veloflow.engine.persistence.model.Workflow;
import com.veloflow.engine.persistence.model.WorkflowEventSubscription;
import com.veloflow.engine.persistence.model.WorkflowInstance;
import com.veloflow.engine.persistence.model.WorkflowNode;
import com.veloflow.engine.runtime.WorkflowRuntimeListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 事件桥与上游触发链（53 号 §3.1/§3.3，P3 二期）：
 * <ul>
 *   <li>onEvent：事件交付——唤醒匹配的 event-wait 等待行 + 自动发起匹配的
 *       event-trigger 已发布流程（config.eventType）</li>
 *   <li>onInstanceCompleted：upstream-trigger 联动——上游实例成功完成后按
 *       config.upstreamWorkflowId 自动发起下游流程（input=上游 finalOutput）</li>
 *   <li>订阅清理：event-wait 节点结算/实例失败终止时幂等取消 waiting 行</li>
 * </ul>
 * 引擎内置 REST 注入端点（/events/{eventType}）；宿主亦可直接调用 {@link #onEvent}
 * 桥接平台事件源。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowEventService implements WorkflowRuntimeListener {

    private final WorkflowEventSubscriptionMapper subscriptionMapper;
    private final WorkflowNodeMapper nodeMapper;
    private final WorkflowMapper workflowMapper;
    /**
     * 延迟解析（断环）：调度器持有本类（监听器列表），本类又需实例服务
     * （completeNodeBySystem/startByTrigger），直接注入构成
     * InstanceService → Scheduler → 监听器 → 本类 → InstanceService 环。
     */
    private final ObjectProvider<WorkflowInstanceService> instanceServiceProvider;

    private WorkflowInstanceService instanceService() {
        return instanceServiceProvider.getObject();
    }

    // ==================== 事件交付 ====================

    /**
     * 事件交付入口：payload 原样作为唤醒输出/触发输入（JSON 文本）。
     *
     * @return 唤醒的等待节点数 + 发起的实例数
     */
    public DeliveryResult onEvent(String eventType, String payloadJson) {
        if (eventType == null || eventType.isBlank()) {
            return new DeliveryResult(0, 0);
        }
        int woken = wakeWaitSubscriptions(eventType.trim(), payloadJson);
        int started = startEventTriggerWorkflows(eventType.trim(), payloadJson);
        log.info("事件交付: eventType={}, woken={}, started={}", eventType, woken, started);
        return new DeliveryResult(woken, started);
    }

    public record DeliveryResult(int wokenWaitNodes, int startedInstances) {
    }

    /** 唤醒匹配 event-wait：consume 命中行 + 取消同节点其余订阅（幂等） */
    private int wakeWaitSubscriptions(String eventType, String payloadJson) {
        List<WorkflowEventSubscription> hits = subscriptionMapper.selectList(
                new LambdaQueryWrapper<WorkflowEventSubscription>()
                        .eq(WorkflowEventSubscription::getEventType, eventType)
                        .eq(WorkflowEventSubscription::getStatus, "waiting"));
        int woken = 0;
        for (WorkflowEventSubscription hit : hits) {
            subscriptionMapper.update(null, new LambdaUpdateWrapper<WorkflowEventSubscription>()
                    .eq(WorkflowEventSubscription::getId, hit.getId())
                    .eq(WorkflowEventSubscription::getStatus, "waiting")
                    .set(WorkflowEventSubscription::getStatus, "consumed")
                    .set(WorkflowEventSubscription::getUpdatedAt, System.currentTimeMillis()));
            cancelNodeSubscriptions(hit.getNodeInstanceId());
            try {
                instanceService().completeNodeBySystem(hit.getInstanceId(),
                        hit.getNodeInstanceId(), payloadJson, "EVENT:" + eventType);
                woken++;
            } catch (Exception e) {
                log.warn("事件唤醒节点失败（实例可能已终结）: subId={}, instanceId={}, err={}",
                        hit.getId(), hit.getInstanceId(), e.getMessage());
            }
        }
        return woken;
    }

    /** 自动发起匹配 event-trigger 的已发布流程（同流程去重） */
    private int startEventTriggerWorkflows(String eventType, String payloadJson) {
        List<WorkflowNode> triggerNodes = nodeMapper.selectList(
                new LambdaQueryWrapper<WorkflowNode>()
                        .eq(WorkflowNode::getNodeType, "event-trigger"));
        Set<String> started = new HashSet<>();
        for (WorkflowNode node : triggerNodes) {
            Map<String, Object> config = parseConfig(node.getConfig());
            if (!eventType.equals(String.valueOf(config.get("eventType")))) {
                continue;
            }
            if (!started.add(node.getWorkflowId())) {
                continue;
            }
            if (!isPublished(node.getWorkflowId())) {
                continue;
            }
            try {
                instanceService().startByTrigger(node.getWorkflowId(), "event", null,
                        payloadJson, "EVENT");
            } catch (Exception e) {
                log.warn("事件触发流程失败: workflowId={}, eventType={}, err={}",
                        node.getWorkflowId(), eventType, e.getMessage());
            }
        }
        return started.size();
    }

    // ==================== upstream 触发链 ====================

    /** 上游实例成功完成 → 匹配 upstream-trigger 的已发布流程自动发起（同流程去重） */
    private int startUpstreamWorkflows(WorkflowInstance upstream) {
        List<WorkflowNode> triggerNodes = nodeMapper.selectList(
                new LambdaQueryWrapper<WorkflowNode>()
                        .eq(WorkflowNode::getNodeType, "upstream-trigger"));
        Set<String> started = new HashSet<>();
        for (WorkflowNode node : triggerNodes) {
            Map<String, Object> config = parseConfig(node.getConfig());
            if (!upstream.getWorkflowId().equals(String.valueOf(config.get("upstreamWorkflowId")))) {
                continue;
            }
            if (!started.add(node.getWorkflowId()) || !isPublished(node.getWorkflowId())) {
                continue;
            }
            try {
                instanceService().startByTrigger(node.getWorkflowId(), "upstream", null,
                        upstream.getFinalOutput() == null ? "" : upstream.getFinalOutput(), "UPSTREAM");
            } catch (Exception e) {
                log.warn("上游触发流程失败: workflowId={}, upstream={}, err={}",
                        node.getWorkflowId(), upstream.getWorkflowId(), e.getMessage());
            }
        }
        return started.size();
    }

    // ==================== 订阅清理 ====================

    /** event-wait 节点结算（完成/超时/失败）后清理残留 waiting 订阅 */
    private void cancelNodeSubscriptions(String nodeInstanceId) {
        if (nodeInstanceId == null) {
            return;
        }
        subscriptionMapper.update(null, new LambdaUpdateWrapper<WorkflowEventSubscription>()
                .eq(WorkflowEventSubscription::getNodeInstanceId, nodeInstanceId)
                .eq(WorkflowEventSubscription::getStatus, "waiting")
                .set(WorkflowEventSubscription::getStatus, "cancelled")
                .set(WorkflowEventSubscription::getUpdatedAt, System.currentTimeMillis()));
    }

    private void cancelInstanceSubscriptions(String instanceId) {
        subscriptionMapper.update(null, new LambdaUpdateWrapper<WorkflowEventSubscription>()
                .eq(WorkflowEventSubscription::getInstanceId, instanceId)
                .eq(WorkflowEventSubscription::getStatus, "waiting")
                .set(WorkflowEventSubscription::getStatus, "cancelled")
                .set(WorkflowEventSubscription::getUpdatedAt, System.currentTimeMillis()));
    }

    // ==================== 运行时监听器 ====================

    @Override
    public void onEventWaitNodeSettled(String instanceId, String nodeInstanceId) {
        cancelNodeSubscriptions(nodeInstanceId);
    }

    @Override
    public void onInstanceCompleted(WorkflowInstance instance) {
        startUpstreamWorkflows(instance);
    }

    @Override
    public void onInstanceCancelled(WorkflowInstance instance) {
        cancelInstanceSubscriptions(instance.getId());
    }

    // ==================== 辅助 ====================

    private boolean isPublished(String workflowId) {
        Workflow workflow = workflowMapper.selectById(workflowId);
        return workflow != null && workflow.getStatus() != null && workflow.getStatus() == 1;
    }

    private Map<String, Object> parseConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return Map.of();
        }
        try {
            return VeloflowJson.MAPPER.readValue(configJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }
}
