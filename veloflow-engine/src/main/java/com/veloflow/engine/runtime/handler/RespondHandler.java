package com.veloflow.engine.runtime.handler;

import com.veloflow.engine.commons.VeloflowJson;
import com.veloflow.engine.runtime.WorkflowNodeContext;
import com.veloflow.engine.runtime.WorkflowNodeHandler;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 同步响应节点（53 号 §3.3）：自动型——把 config.payload（缺省透传上游输出）写为
 * 实例 respondPayload（调度器落列并通知监听器）；webhook 触发链路据此同步返回，
 * 等待超时回退 202。
 */
@Component
public class RespondHandler implements WorkflowNodeHandler {

    @Override
    public String type() {
        return "respond";
    }

    @Override
    public NodeKind kind() {
        return NodeKind.AUTO;
    }

    @Override
    public void activate(WorkflowNodeContext context) {
        Map<String, Object> config = context.config();
        String output;
        if (config.containsKey("payload") && config.get("payload") != null) {
            output = VeloflowJson.MAPPER.valueToTree(config.get("payload")).toString();
        } else {
            output = context.upstreamOutput() == null ? "" : context.upstreamOutput();
        }
        context.complete(output);
    }
}
