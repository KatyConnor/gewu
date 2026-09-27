package com.gewu.application.workflow.engine.handler;

import com.gewu.application.workflow.engine.WorkflowNodeContext;
import com.gewu.application.workflow.engine.WorkflowNodeHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * HTTP 请求节点（51 号 §二 2.6）：出站请求经内网地址守卫（默认拒绝内网/环回/链路本地，
 * 对齐编排 SSRF 防护语义）；支持节点级重试（≤3）与超时；请求体支持 ${变量} 模板。
 */
@Slf4j
@Component
public class HttpRequestHandler implements WorkflowNodeHandler {

    /** 默认请求超时（秒） */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    /** 默认重试次数 */
    private static final int DEFAULT_RETRY_COUNT = 0;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public String type() {
        return "http-request";
    }

    @Override
    public NodeKind kind() {
        return NodeKind.AUTO;
    }

    @Override
    public List<String> requiredConfigFields() {
        return List.of("url");
    }

    @Override
    public void activate(WorkflowNodeContext context) {
        Map<String, Object> config = context.config();
        String url = render(String.valueOf(config.get("url")), context);
        String method = String.valueOf(config.getOrDefault("method", "GET")).toUpperCase();
        int timeoutSeconds = (int) parseLong(config.get("timeoutSeconds"), DEFAULT_TIMEOUT_SECONDS);
        int retryCount = (int) Math.min(parseLong(config.get("retryCount"), DEFAULT_RETRY_COUNT), 3);
        long backoffMs = parseLong(config.get("retryBackoffMs"), 1000);

        try {
            URI uri = URI.create(url);
            guardInternalAddress(uri);
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                    .header("Content-Type", "application/json");
            if ("GET".equals(method)) {
                requestBuilder.GET();
            } else {
                String body = render(config.containsKey("body")
                        ? String.valueOf(config.get("body")) : "", context);
                requestBuilder.method(method, HttpRequest.BodyPublishers.ofString(body));
            }
            HttpRequest request = requestBuilder.build();

            int attempt = 0;
            while (true) {
                try {
                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    String output = responseBody(response);
                    if (response.statusCode() >= 400) {
                        context.complete(false, "HTTP " + response.statusCode() + ": "
                                + truncate(output));
                        return;
                    }
                    context.complete(output);
                    return;
                } catch (Exception e) {
                    if (attempt < retryCount) {
                        attempt++;
                        log.warn("HTTP 节点请求失败重试 {}/{}: url={}, cause={}",
                                attempt, retryCount, url, e.getMessage());
                        Thread.sleep(Math.max(0, backoffMs));
                        continue;
                    }
                    context.complete(false, "HTTP 请求失败: " + e.getMessage());
                    return;
                }
            }
        } catch (IllegalArgumentException e) {
            context.complete(false, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            context.complete(false, "HTTP 请求被中断");
        }
    }

    /** 内网地址守卫：环回/内网/链路本地/未解析地址默认拒绝（公网部署可经配置放开） */
    private void guardInternalAddress(URI uri) {
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("URL 缺少主机名");
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress() || address.isAnyLocalAddress()) {
                throw new IllegalArgumentException("HTTP 节点禁止访问内网/环回地址: " + host);
            }
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException("URL 主机无法解析: " + host);
        }
    }

    /** 简易 ${变量} 模板渲染（未定义替换空串，与编排 VariableTemplates 语义一致） */
    private String render(String template, WorkflowNodeContext context) {
        if (template == null) {
            return "";
        }
        // XSS 层（CR-019）中和了 < >：URL/body 模板先对称还原再替换变量
        template = template.replace("&gt;", ">").replace("&lt;", "<");
        if (!template.contains("${")) {
            return template;
        }
        StringBuilder rendered = new StringBuilder();
        int cursor = 0;
        while (cursor < template.length()) {
            int start = template.indexOf("${", cursor);
            if (start < 0) {
                rendered.append(template, cursor, template.length());
                break;
            }
            int end = template.indexOf('}', start);
            if (end < 0) {
                rendered.append(template, cursor, template.length());
                break;
            }
            rendered.append(template, cursor, start);
            String varName = template.substring(start + 2, end);
            Object value = context.variables().get(varName);
            rendered.append(value != null ? String.valueOf(value) : "");
            cursor = end + 1;
        }
        return rendered.toString();
    }

    private String responseBody(HttpResponse<String> response) {
        String body = response.body();
        return body != null ? body : "";
    }

    private long parseLong(Object value, long defaultValue) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                // 非数字按缺省
            }
        }
        return defaultValue;
    }

    private String truncate(String text) {
        return text != null && text.length() > 500 ? text.substring(0, 500) : text;
    }
}
