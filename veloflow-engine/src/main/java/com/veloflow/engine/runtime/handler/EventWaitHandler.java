package com.veloflow.engine.runtime.handler;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.veloflow.engine.commons.VlfId;
import com.veloflow.engine.persistence.mapper.WorkflowEventSubscriptionMapper;
import com.veloflow.engine.persistence.model.WorkflowEventSubscription;
import com.veloflow.engine.runtime.WorkflowNodeContext;
import com.veloflow.engine.runtime.WorkflowNodeHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 事件等待节点（53 号 §3.3，Event Gateway 对位）：等待型——挂起至订阅事件之一到达。
 * 激活时按 eventTypes（数组或单值 eventType）各登记一条 WAIT 订阅；
 * 事件交付命中后推进，同组其余订阅取消（onEventWaitNodeSettled 兜底清理）。
 * <p>可选 timeoutHours 超时（超时动作同 task）。
 */
@Component
@RequiredArgsConstructor
public class EventWaitHandler implements WorkflowNodeHandler {

    private final WorkflowEventSubscriptionMapper subscriptionMapper;

    @Override
    public String type() {
        return "event-wait";
    }

    @Override
    public NodeKind kind() {
        return NodeKind.WAITING;
    }

    @Override
    public List<String> requiredConfigFields() {
        return List.of("eventTypes");
    }

    @Override
    public void activate(WorkflowNodeContext context) {
        List<String> eventTypes = parseEventTypes(context.config());
        if (eventTypes.isEmpty()) {
            context.complete(false, "event-wait 缺少 eventTypes/eventType 配置");
            return;
        }
        long now = System.currentTimeMillis();
        for (String eventType : eventTypes) {
            WorkflowEventSubscription sub = new WorkflowEventSubscription();
            sub.setId(VlfId.next());
            sub.setSubscriptionType("WAIT");
            sub.setWorkflowId(context.instance().getWorkflowId());
            sub.setInstanceId(context.instance().getId());
            sub.setNodeId(context.node().getId());
            sub.setNodeInstanceId(context.nodeInstance().getId());
            sub.setEventType(eventType);
            sub.setStatus("waiting");
            sub.setCreatedAt(now);
            sub.setUpdatedAt(now);
            subscriptionMapper.insert(sub);
        }
        TaskHandler.applyTimeout(context, context.config());
    }

    /** 事件交付命中后取消同节点其余订阅（幂等：仅 waiting 行置 cancelled） */
    public void cancelSiblingSubscriptions(String nodeInstanceId) {
        subscriptionMapper.update(null, new LambdaUpdateWrapper<WorkflowEventSubscription>()
                .eq(WorkflowEventSubscription::getNodeInstanceId, nodeInstanceId)
                .eq(WorkflowEventSubscription::getStatus, "waiting")
                .set(WorkflowEventSubscription::getStatus, "cancelled")
                .set(WorkflowEventSubscription::getUpdatedAt, System.currentTimeMillis()));
    }

    /** config 解析订阅事件类型：eventTypes 数组优先，回退单值 eventType */
    static List<String> parseEventTypes(Map<String, Object> config) {
        List<String> result = new ArrayList<>();
        Object list = config.get("eventTypes");
        if (list instanceof List<?> items) {
            for (Object item : items) {
                String type = item == null ? "" : String.valueOf(item).trim();
                if (!type.isEmpty()) {
                    result.add(type);
                }
            }
        }
        if (result.isEmpty()) {
            Object single = config.get("eventType");
            if (single != null && !String.valueOf(single).isBlank()) {
                result.add(String.valueOf(single).trim());
            }
        }
        return result;
    }
}
