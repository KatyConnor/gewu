package com.veloflow.engine.runtime.handler;

import com.veloflow.engine.commons.FlowTemplates;
import com.veloflow.engine.commons.VeloflowJson;
import com.veloflow.engine.runtime.WorkflowNodeContext;
import com.veloflow.engine.runtime.WorkflowNodeHandler;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IM 推送节点（51 号 §2.6，53 号 §3.6）：webhook 地址入宿主配置不落流程定义——
 * channel → veloflow.im.webhooks.<channel> 配置的 URL，HTTP POST 推送文本消息
 * （钉钉/企微/飞书 text 格式）。未配置渠道以可读错误完成。
 * <p>config：channel（必填 dingtalk/wecom/feishu）、messageTemplate（必填，支持 ${变量}）。
 */
@Component
public class ImNotifyHandler implements WorkflowNodeHandler {

    private static final List<String> CHANNELS = List.of("dingtalk", "wecom", "feishu");
    private static final String WEBHOOK_PREFIX = "veloflow.im.webhooks.";

    private final Environment environment;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public ImNotifyHandler(Environment environment) {
        this.environment = environment;
    }

    @Override
    public String type() {
        return "im-notify";
    }

    @Override
    public NodeKind kind() {
        return NodeKind.AUTO;
    }

    @Override
    public List<String> requiredConfigFields() {
        return List.of("channel", "messageTemplate");
    }

    @Override
    public void activate(WorkflowNodeContext context) {
        Map<String, Object> config = context.config();
        try {
            String channel = String.valueOf(config.getOrDefault("channel", "")).trim().toLowerCase();
            String webhook = environment.getProperty(WEBHOOK_PREFIX + channel);
            if (webhook == null || webhook.isBlank()) {
                context.complete(false, "IM 渠道 " + channel + " 未配置 webhook"
                        + "（veloflow.im.webhooks." + channel + "）");
                return;
            }
            String message = FlowTemplates.render(
                    String.valueOf(config.getOrDefault("messageTemplate", "")), context.variables());
            String payload = textPayload(channel, message);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhook))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                context.complete(false, "IM 推送失败: HTTP " + response.statusCode()
                        + " " + truncate(response.body()));
                return;
            }
            LinkedHashMap<String, Object> output = new LinkedHashMap<>();
            output.put("channel", channel);
            output.put("status", "sent");
            context.complete(VeloflowJson.MAPPER.valueToTree(output).toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            context.complete(false, "IM 推送被中断");
        } catch (Exception e) {
            context.complete(false, "IM 推送失败: " + e.getMessage());
        }
    }

    /** 各平台 text 消息格式（钉钉/企微同构，飞书独立） */
    static String textPayload(String channel, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        if ("feishu".equals(channel)) {
            body.put("msg_type", "text");
            body.put("content", Map.of("text", message));
        } else {
            body.put("msgtype", "text");
            body.put("text", Map.of("content", message));
        }
        return VeloflowJson.MAPPER.valueToTree(body).toString();
    }

    private static String truncate(String s) {
        return s != null && s.length() > 120 ? s.substring(0, 120) : s;
    }
}
