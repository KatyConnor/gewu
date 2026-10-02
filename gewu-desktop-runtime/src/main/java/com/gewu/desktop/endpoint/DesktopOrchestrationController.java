package com.gewu.desktop.endpoint;

import com.gewu.agent.engine.core.event.AgentEvent;
import com.gewu.agent.engine.orchestration.OrchestrationEngine;
import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import com.gewu.agent.engine.orchestration.model.OrchestrationGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 引擎 API Profile：编排域（D2-6，与平台 /orchestration 同构的最小集）。
 * 引擎编排能力本地可用：PIPELINE/SUPERVISOR/SWARM/DEBATE 四模式 + 自主目标 +
 * 生命周期（pause/resume/cancel——引擎 ExecutionControl 真实现，桌面单机
 * 检查点为内存态：重启即放弃并在 UI 明示，docs/design/42 §7.6）。
 * <p>图定义直通引擎 Jackson 反序列化（OrchestrationGraph 契约与平台一致）。
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/v1/orchestration")
public class DesktopOrchestrationController {

    private final OrchestrationEngine engine;
    private final ObjectMapper objectMapper;

    /** 活跃流的 emitter 注册（生命周期操作需要向同一流推送状态事件；本控制器内自管） */
    private final Map<String, SseEmitter> streamEmitters = new ConcurrentHashMap<>();

    public DesktopOrchestrationController(OrchestrationEngine engine, ObjectMapper objectMapper) {
        this.engine = engine;
        this.objectMapper = objectMapper;
    }

    /** 运行目标（input）：播种进上下文供 PIPELINE 起始输入 / DEBATE 辩题 / ${var.input} 模板引用 */
    public record ExecuteRequest(OrchestrationGraph graph, String sessionId, String input) {
    }

    /** 流式执行（SSE，事件契约与平台编排流一致：graph_start/node_x/graph_complete） */
    @PostMapping("/graphs/execute/stream")
    public SseEmitter executeStream(@RequestBody ExecuteRequest req) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            OrchestrationContext ctx = OrchestrationContext.builder()
                    .executionId("exec-" + System.currentTimeMillis())
                    .sessionId(req.sessionId() == null ? "orch-session" : req.sessionId())
                    .userId("local")
                    .build();
            // 上下文变量播种（与平台 OrchestrationService#buildContextVariables 同构）：
            // 图定义 variables 先入（modelProvider/modelName 节点兜底所需），运行时 input 后入覆盖同名键
            Map<String, Object> vars = new LinkedHashMap<>();
            if (req.graph() != null && req.graph().getVariables() != null) {
                vars.putAll(req.graph().getVariables());
            }
            vars.put("input", req.input() == null ? "" : req.input());
            ctx.setVariables(vars);
            final String execId = ctx.getExecutionId();
            streamEmitters.put(execId, emitter);

            engine.executeStream(req.graph(), ctx).subscribe(
                    event -> {
                        try {
                            synchronized (emitter) {
                                emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(toDTO(event, execId))));
                            }
                        } catch (Exception ignored) {
                            // 客户端断开
                        }
                    },
                    error -> {
                        try {
                            synchronized (emitter) {
                                emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(Map.of(
                                        "type", "error", "errorMessage",
                                        error.getMessage() == null ? "编排执行失败" : error.getMessage()))));
                            }
                        } catch (Exception ignored) {
                            // 忽略
                        } finally {
                            streamEmitters.remove(execId);
                        }
                        emitter.complete();
                    },
                    () -> {
                        streamEmitters.remove(execId);
                        emitter.complete();
                    });
        } catch (Exception e) {
            try {
                emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(Map.of(
                        "type", "error", "errorMessage", e.getMessage() == null ? "编排启动失败" : e.getMessage()))));
            } catch (Exception ignored) {
                // 忽略
            }
            emitter.complete();
        }
        return emitter;
    }

    /** 生命周期：暂停（协作式——当前节点完成后生效，ExecutionControl 检查点内存态） */
    @PostMapping("/executions/{executionId}/pause")
    public Map<String, Object> pause(@PathVariable String executionId) {
        engine.pause(executionId);
        return Map.of("ok", true);
    }

    /** 恢复（从检查点续跑） */
    @PostMapping("/executions/{executionId}/resume")
    public SseEmitter resume(@PathVariable String executionId) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            engine.resume(executionId).subscribe(
                    event -> {
                        try {
                            synchronized (emitter) {
                                emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(toDTO(event, executionId))));
                            }
                        } catch (Exception ignored) {
                            // 忽略
                        }
                    },
                    error -> emitter.complete(),
                    emitter::complete);
        } catch (Exception e) {
            throw new IllegalArgumentException("恢复失败: " + e.getMessage());
        }
        return emitter;
    }

    /** 取消 */
    @PostMapping("/executions/{executionId}/cancel")
    public Map<String, Object> cancel(@PathVariable String executionId) {
        engine.cancel(executionId);
        return Map.of("ok", true);
    }

    /** 可暂停性查询（UI 按钮态依据） */
    @GetMapping("/executions/{executionId}/pausable")
    public Map<String, Object> pausable(@PathVariable String executionId) {
        return Map.of("pausable", engine.isPausable(executionId));
    }

    private static Map<String, Object> toDTO(AgentEvent event, String executionId) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("type", event.getType());
        dto.put("content", event.getContent());
        dto.put("reasoning", event.getReasoning());
        dto.put("executionId", executionId);
        dto.put("nodeId", event.getNodeId());
        dto.put("role", event.getRole());
        Object meta = event.getMetadata();
        if (meta != null) {
            dto.put("metadata", meta);
        }
        return dto;
    }
}
