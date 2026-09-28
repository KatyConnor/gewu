package com.veloflow.engine.runtime.handler;

import com.veloflow.engine.runtime.WorkflowNodeContext;
import com.veloflow.engine.runtime.WorkflowNodeHandler;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 消息等待节点（53 号 §3.3）：等待型——激活即挂起并登记 messageKey，
 * 外部系统经消息交付端点携 key 推进（实例内交付可省 key，命中首条等待消息）。
 * <p>可选 timeoutHours 超时（超时动作同 task，由定时器扫描器驱动）。
 */
@Component
public class ReceiveMessageHandler implements WorkflowNodeHandler {

    @Override
    public String type() {
        return "receive-message";
    }

    @Override
    public NodeKind kind() {
        return NodeKind.WAITING;
    }

    @Override
    public List<String> requiredConfigFields() {
        return List.of("messageKey");
    }

    @Override
    public void activate(WorkflowNodeContext context) {
        Object key = context.config().get("messageKey");
        if (key != null && !String.valueOf(key).isBlank()) {
            context.nodeInstance().setMessageKey(String.valueOf(key).trim());
        }
        TaskHandler.applyTimeout(context, context.config());
    }
}
