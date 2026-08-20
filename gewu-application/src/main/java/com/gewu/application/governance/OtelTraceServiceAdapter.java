package com.gewu.application.governance;

import com.gewu.agent.engine.spi.TraceService;
import com.gewu.infrastructure.trace.OrchestrationTracer;
import io.micrometer.tracing.Span;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * OTel 追踪桥接适配器 - TraceService SPI 的落地实现。
 * <p>替换 AgentEngineAutoConfiguration 中的 stub Bean，将引擎内的
 * LLM 调用/工具执行/迭代 Span 桥接到 Micrometer Tracing
 * （OpenTelemetry Bridge -> OTLP 导出），单条轨迹同时以短 Span 记录。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OtelTraceServiceAdapter implements TraceService {

    private final OrchestrationTracer orchestrationTracer;

    @Override
    public void recordTrace(String executionId, String nodeId, String phase, String action, String detail) {
        try {
            Span span = orchestrationTracer.startSpan(executionId, nodeId, phase);
            orchestrationTracer.tag(span, "action", action != null ? action : "");
            if (detail != null && detail.length() > 200) {
                detail = detail.substring(0, 200);
            }
            orchestrationTracer.tag(span, "detail", detail != null ? detail : "");
            orchestrationTracer.endSpan(span);
        } catch (Exception e) {
            log.debug("recordTrace 桥接失败: {}", e.getMessage());
        }
    }

    @Override
    public Object startSpan(String executionId, String nodeId, String operation) {
        try {
            return orchestrationTracer.startSpan(executionId, nodeId, operation);
        } catch (Exception e) {
            log.debug("startSpan 桥接失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public void endSpan(Object span) {
        if (span instanceof Span s) {
            orchestrationTracer.endSpan(s);
        }
    }

    @Override
    public void endSpanWithError(Object span, Throwable error) {
        if (span instanceof Span s) {
            orchestrationTracer.endSpanWithError(s, error);
        }
    }

    @Override
    public List<Map<String, Object>> queryTraces(String executionId) {
        return List.of();
    }

    @Override
    public List<Map<String, Object>> queryCollaborationTraces(String collaborationId) {
        return List.of();
    }
}
