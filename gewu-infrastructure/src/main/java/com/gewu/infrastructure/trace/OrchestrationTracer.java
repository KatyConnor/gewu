package com.gewu.infrastructure.trace;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 编排追踪仪表化 - 使用 Micrometer Tracing（OpenTelemetry Bridge）记录分布式追踪 Span。
 * <p>L2 应用层可观测性组件，为每轮 LLM 调用、工具执行、编排迭代创建 Span。
 * <p>依赖 {@code io.micrometer:micrometer-tracing-bridge-otel} 自动桥接到 OpenTelemetry。
 *
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrchestrationTracer {

    private final Tracer tracer;

    /**
     * 开始一个 Span。
     *
     * @param executionId 执行实例 ID
     * @param nodeId      节点 ID（可为 null）
     * @param operation   操作名称（如 llm_call / tool_call / verify_goal）
     * @return Span 实例
     */
    public Span startSpan(String executionId, String nodeId, String operation) {
        Span span = tracer.nextSpan().name("agent." + operation);
        span.tag("executionId", executionId != null ? executionId : "unknown");
        if (nodeId != null) {
            span.tag("nodeId", nodeId);
        }
        span.start();
        log.debug("OrchestrationTracer.startSpan: execution={}, node={}, op={}", executionId, nodeId, operation);
        return span;
    }

    /**
     * 结束 Span（成功）。
     */
    public void endSpan(Span span) {
        if (span != null) {
            span.tag("status", "SUCCESS");
            span.end();
        }
    }

    /**
     * 结束 Span（失败）。
     */
    public void endSpanWithError(Span span, Throwable error) {
        if (span != null) {
            span.tag("status", "FAILED");
            if (error != null) {
                span.tag("error", error.getClass().getSimpleName());
                span.tag("errorMessage", error.getMessage() != null ? error.getMessage() : "unknown");
            }
            span.end();
        }
    }

    /**
     * 向 Span 添加事件。
     */
    public void addEvent(Span span, String name, Map<String, String> attributes) {
        if (span != null && attributes != null) {
            attributes.forEach(span::tag);
        }
    }

    /**
     * 向 Span 添加标签。
     */
    public void tag(Span span, String key, String value) {
        if (span != null) {
            span.tag(key, value);
        }
    }
}