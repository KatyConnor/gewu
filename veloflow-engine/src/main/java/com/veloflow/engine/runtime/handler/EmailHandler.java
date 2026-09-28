package com.veloflow.engine.runtime.handler;

import com.veloflow.engine.ai.FlowMailBridge;
import com.veloflow.engine.runtime.WorkflowNodeContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 发送邮件节点（51 号 §2.6，53 号 §3.6）：SMTP 配置化由宿主管理（spring.mail.*），
 * 经 {@link FlowMailBridge} 发送；桥未接入/未配置时以可读错误完成。
 * <p>config：to（必填，支持 ${变量}）、subject（必填）、body（模板，支持 ${变量}）。
 */
@Component
public class EmailHandler extends AiNodeHandler {

    private final ObjectProvider<FlowMailBridge> bridgeProvider;

    public EmailHandler(ObjectProvider<FlowMailBridge> bridgeProvider) {
        this.bridgeProvider = bridgeProvider;
    }

    @Override
    public String type() {
        return "email";
    }

    @Override
    public NodeKind kind() {
        return NodeKind.AUTO;
    }

    @Override
    public List<String> requiredConfigFields() {
        return List.of("to", "subject");
    }

    @Override
    protected String nodeLabel() {
        return "发送邮件";
    }

    @Override
    public void activate(WorkflowNodeContext context) {
        Map<String, Object> config = context.config();
        FlowMailBridge bridge = bridgeProvider.getIfAvailable();
        if (bridge == null) {
            context.complete(false, "邮件桥未接入（宿主未提供 FlowMailBridge 实现，"
                    + "需配置 veloflow.mail.enabled=true 与 spring.mail.host）");
            return;
        }
        completeSafely(context, () -> {
            String to = render(str(config, "to"), context.variables()).trim();
            String subject = render(str(config, "subject"), context.variables());
            String body = render(str(config, "body"), context.variables());
            String messageId = bridge.send(to, subject, body);
            context.complete(toJson("sent", to, "messageId", messageId));
        });
    }

    private static String toJson(String k1, String v1, String k2, String v2) {
        return "{\"" + k1 + "\": \"" + v1.replace("\"", "\\\"")
                + "\", \"" + k2 + "\": \"" + (v2 == null ? "" : v2.replace("\"", "\\\"")) + "\"}";
    }
}
