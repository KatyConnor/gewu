package com.gewu.desktop.endpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.core.AgentEngine;
import com.gewu.agent.engine.core.AgentTask;
import com.gewu.desktop.adapter.DesktopPersistenceService;
import com.gewu.desktop.adapter.DesktopSessionStore;
import com.gewu.desktop.llm.DesktopModelCatalog;
import com.gewu.desktop.security.ApprovalBroker;
import com.gewu.agent.engine.spi.AgentSpec;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 引擎 API Profile：chat / chat-stream / models（D1-12）。
 * <p>契约与平台 AiChatController 同构：SSE data 帧逐事件推送、[DONE] 哨兵、error 事件兜底。
 * 持久化归属：流开始即落用户消息（clientId 幂等），done 落助手消息——崩溃不丢、重放零副作用。
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/v1")
public class DesktopChatController {

    /** 内置默认 Agent（无任何本地 Agent 时的兜底） */
    private static final String DEFAULT_AGENT_ID = "desktop-assistant";

    private final AgentEngine agentEngine;
    private final ObjectMapper objectMapper;
    private final DesktopSessionStore sessionStore;
    private final DesktopPersistenceService persistenceService;
    private final com.gewu.agent.engine.llm.LlmProvider llmProvider;
    private final DesktopModelCatalog modelCatalog;
    private final ApprovalBroker approvalBroker;
    private final com.gewu.desktop.adapter.LocalFileService fileChangeService;
    private final ActiveStreamRegistry activeStreams;
    private final com.gewu.desktop.entry.EntryFileResolver entryFileResolver;

    public DesktopChatController(AgentEngine agentEngine, ObjectMapper objectMapper,
                                 DesktopSessionStore sessionStore,
                                 DesktopPersistenceService persistenceService,
                                 com.gewu.agent.engine.llm.LlmProvider llmProvider,
                                 DesktopModelCatalog modelCatalog,
                                 ApprovalBroker approvalBroker,
                                 com.gewu.desktop.adapter.LocalFileService fileChangeService,
                     ActiveStreamRegistry activeStreams,
                     com.gewu.desktop.entry.EntryFileResolver entryFileResolver) {
        this.agentEngine = agentEngine;
        this.objectMapper = objectMapper;
        this.sessionStore = sessionStore;
        this.persistenceService = persistenceService;
        this.llmProvider = llmProvider;
        this.modelCatalog = modelCatalog;
        this.approvalBroker = approvalBroker;
        this.fileChangeService = fileChangeService;
        this.activeStreams = activeStreams;
        this.entryFileResolver = entryFileResolver;
    }

    @GetMapping("/engine/health")
    public Map<String, Object> health() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("status", "UP");
        info.put("module", "gewu-desktop-runtime");
        info.put("phase", "mvp");
        info.put("engine", "gewu-agent-engine");
        return info;
    }

    public record ChatStreamRequest(String message, String sessionId, String agentId,
                                    String model, String clientId,
                                    String projectId, String directory) {
    }

    // ==================== Agent 解析 ====================

    /** Agent 解析：显式 agentId ＞ 首个启用 Agent ＞ 内置默认（懒创建） */
    private AgentSpec resolveAgent(String agentId) {
        if (agentId != null && !agentId.isBlank()) {
            AgentSpec spec = persistenceService.loadAgent(agentId);
            if (spec == null) {
                throw new IllegalArgumentException("Agent 不存在: " + agentId);
            }
            return spec;
        }
        List<AgentSpec> agents = persistenceService.listAgents().stream()
                .filter(a -> a.getStatus() == null || a.getStatus() == 1).toList();
        if (!agents.isEmpty()) {
            return agents.get(0);
        }
        String provider = llmProvider.listProviderCodes().isEmpty() ? null : llmProvider.listProviderCodes().get(0);
        persistenceService.upsertAgent(DEFAULT_AGENT_ID, "桌面助手", "格物桌面端内置助手",
                provider, null, "你是格物桌面端智能助手，用中文简洁、准确地回答问题。", 1);
        return persistenceService.loadAgent(DEFAULT_AGENT_ID);
    }

    /**
     * 模型解析（带回退）：Agent 固化的供应商标识可能已被删除（设置页删除/禁用），
     * 此时回退到当前首个可用供应商——避免 Agent 挂死在失效配置上（V1.2 用户实测）。
     * 模型名兜底链：请求显式指定 ＞ Agent 固化 ＞ 供应商模型清单第一个（用户实测：表单无
     * 模型名时引擎报 MODEL_NOT_RESOLVED）。供应商为空时返回 [null, null]。
     */
    private String[] resolveModel(AgentSpec agent, String requestModel) {
        List<String> enabled = llmProvider.listProviderCodes();
        if (enabled.isEmpty()) {
            return new String[]{null, null};
        }
        String provider = agent.getModelProvider() != null && enabled.contains(agent.getModelProvider())
                ? agent.getModelProvider() : enabled.get(0);
        String model = null;
        if (requestModel != null && !requestModel.isBlank()) {
            model = requestModel;
        } else if (agent.getModelName() != null && provider.equals(agent.getModelProvider())) {
            model = agent.getModelName();
        } else {
            model = modelCatalog.firstModelName(provider);
        }
        return new String[]{provider, model};
    }

    // ==================== 流式对话 ====================

    @PostMapping(value = "/ai/chat/stream")
    public SseEmitter chatStream(@RequestBody ChatStreamRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            AgentSpec agent = resolveAgent(request.agentId());
            String sessionId = ensureSession(agent, request);
            String clientId = request.clientId();
            String userClientId = clientId == null || clientId.isBlank() ? null : clientId + ":u";
            // 用户消息先落库（幂等；崩溃/断线不丢）
            sessionStore.appendMessage(sessionId, "user", request.message(), userClientId, null);

            String[] pm = resolveModel(agent, request.model());
            if (pm[0] == null) {
                // 无可用供应商：给用户可操作的提示，而非引擎内部错误码
                emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(Map.of(
                        "type", "error",
                        "errorMessage", "尚未配置模型供应商，请点击左侧「设置」添加（编码/BaseURL/Key）"))));
                emitter.complete();
                return emitter;
            }

            AgentTask task = AgentTask.builder()
                    .agentId(agent.getId())
                    .sessionId(sessionId)
                    .userId("local")
                    .message(request.message())
                    // 显式工作区根:随任务透传(含 spawn_subagents 子代理),文件工具域指向会话绑定目录
                    .workspaceRoot(sessionStore.getDirectory(sessionId))
                    .build();
            task.setModelProvider(pm[0]);
            task.setModelName(pm[1]);
            applyEntryDirectives(task);

            StringBuilder content = new StringBuilder();
            final java.util.concurrent.atomic.AtomicReference<Object> doneStats =
                    new java.util.concurrent.atomic.AtomicReference<>();
            approvalBroker.registerEmitter(sessionId, emitter);
            // 活动流登记:执行中转向(用户插话)的判定依据,引擎流终止时注销(与前端连接无关)
            activeStreams.register(sessionId);
            agentEngine.executeStream(task).subscribe(
                    event -> {
                        try {
                            if ("content".equals(event.getType()) && event.getContent() != null) {
                                content.append(event.getContent());
                            }
                            if ("done".equals(event.getType()) && event.getMetadata() != null) {
                                doneStats.set(event.getMetadata());
                            }
                            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(event)));
                        } catch (Exception e) {
                            // 客户端断开：流终止由引擎 onErrorResume/complete 收尾
                        }
                    },
                    error -> {
                        try {
                            Map<String, Object> err = new LinkedHashMap<>();
                            err.put("type", "error");
                            err.put("errorMessage", error.getMessage() == null
                                    ? error.getClass().getSimpleName() : error.getMessage());
                            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(err)));
                        } catch (Exception ignored) {
                            // 忽略
                        }
                        emitter.complete();
                        activeStreams.unregister(sessionId);
                    },
                    () -> {
                        try {
                            if (content.length() > 0) {
                                // 助手消息落库（幂等键 = clientId:a）——metadata 带 done.stats（尾部收尾行）
                                String assistantClientId = clientId == null || clientId.isBlank()
                                        ? null : clientId + ":a";
                                String meta = doneStats.get() == null ? null
                                        : objectMapper.writeValueAsString(Map.of("stats", doneStats.get()));
                                sessionStore.appendMessage(sessionId, "assistant", content.toString(),
                                        assistantClientId, meta);
                            }
                            emitter.send(SseEmitter.event().data("[DONE]"));
                        } catch (Exception ignored) {
                            // 忽略
                        } finally {
                            approvalBroker.unregisterEmitter(sessionId);
                        }
                        emitter.complete();
                        activeStreams.unregister(sessionId);
                    });
        } catch (Exception e) {
            // 同步段异常（会话/Agent 解析失败）：以 error 事件兜底，契约与平台一致
            try {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("type", "error");
                err.put("errorMessage", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(err)));
            } catch (Exception ignored) {
                // 忽略
            }
            emitter.complete();
        }
        return emitter;
    }

    // ==================== 同步对话 ====================

    public record ChatResponse(String messageId, String content, String finishReason) {
    }

    @PostMapping("/ai/chat")
    public Map<String, Object> chat(@RequestBody ChatStreamRequest request) {
        AgentSpec agent = resolveAgent(request.agentId());
        String sessionId = ensureSession(agent, request);
        String userClientId = request.clientId() == null || request.clientId().isBlank()
                ? null : request.clientId() + ":u";
        sessionStore.appendMessage(sessionId, "user", request.message(), userClientId, null);

        AgentTask task = AgentTask.builder()
                .agentId(agent.getId())
                .sessionId(sessionId)
                .userId("local")
                .message(request.message())
                .workspaceRoot(sessionStore.getDirectory(sessionId))
                .build();
        String[] pmSync = resolveModel(agent, request.model());
        task.setModelProvider(pmSync[0]);
        task.setModelName(pmSync[1]);
        applyEntryDirectives(task);

        StringBuilder content = new StringBuilder();
        String finishReason = null;
        // 同步路径同样登记活动流:引擎同步执行同样按轮次边界消费转向队列
        activeStreams.register(sessionId);
        try {
            com.gewu.agent.engine.llm.model.LlmResponse resp =
                    agentEngine.execute(task);
            if (resp.getContent() != null) {
                content.append(resp.getContent());
            }
            finishReason = resp.getFinishReason();
            if (content.length() > 0) {
                sessionStore.appendMessage(sessionId, "assistant", content.toString(), null, null);
            }
        } finally {
            activeStreams.unregister(sessionId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("messageId", sessionId + ":last");
        result.put("content", content.toString());
        result.put("finishReason", finishReason);
        return result;
    }

    // ==================== 过程时间线持久化（D1-24 收尾：渲染层快照回写） ====================

    public record ProcessPersistRequest(java.util.List<Map<String, Object>> process, Long processMs,
                                        java.util.Map<String, Object> stats) {
    }

    /** 渲染层流结束后回写过程快照与执行统计（ProcessItem 原生形状；重进会话还原时间线与收尾行） */
    @org.springframework.web.bind.annotation.PutMapping("/ai/sessions/{sessionId}/messages/last/process")
    public Map<String, Object> persistProcess(@org.springframework.web.bind.annotation.PathVariable String sessionId,
                                              @RequestBody ProcessPersistRequest req) throws Exception {
        java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("process", req.process() == null ? List.of() : req.process());
        metadata.put("processMs", req.processMs() == null ? 0 : req.processMs());
        if (req.stats() != null) {
            metadata.put("stats", req.stats());
        }
        String metaJson = objectMapper.writeValueAsString(metadata);
        int n = sessionStore.updateLastAssistantMetadata(sessionId, metaJson);
        // 同步更新会话行 metadata？会话行无该列——返回即可
        return Map.of("ok", n > 0);
    }

    /** 工作区 Git 分支（顶栏 chip；非 git 目录 data.branch=null） */
    @org.springframework.web.bind.annotation.GetMapping("/sessions/{sessionId}/git-info")
    public Map<String, Object> gitInfo(@org.springframework.web.bind.annotation.PathVariable String sessionId) {
        return Map.of("branch", fileChangeService.gitBranch(sessionId));
    }

    // ==================== 模型清单 ====================

    /** GET /ai/models：与 @gewu/agent-ui/protocol ModelInfo 同构 */
    @GetMapping("/ai/models")
    public List<Map<String, Object>> models() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DesktopModelCatalog.ModelEntry entry : modelCatalog.listModels()) {
            try {
                List<Map<String, Object>> models = objectMapper.readValue(entry.modelsJson, List.class);
                for (Map<String, Object> m : models) {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("provider", entry.provider);
                    info.put("name", entry.displayName + " / " + m.getOrDefault("name", ""));
                    info.put("modelName", m.getOrDefault("name", ""));
                    info.put("displayName", m.getOrDefault("displayName", m.getOrDefault("name", "")));
                    info.put("description", m.getOrDefault("description", ""));
                    info.put("supported", true);
                    out.add(info);
                }
            } catch (Exception ignored) {
                // 单个供应商 models_json 解析失败不阻塞清单
            }
        }
        return out;
    }

    // ==================== 会话保障 ====================

    private String ensureSession(AgentSpec agent, ChatStreamRequest request) {
        if (request.sessionId() != null && !request.sessionId().isBlank()) {
            Map<String, Object> s = sessionStore.getSession(request.sessionId());
            if (s == null) {
                throw new IllegalArgumentException("会话不存在: " + request.sessionId());
            }
            return request.sessionId();
        }
        String title = request.message() == null ? "新对话"
                : request.message().replaceAll("\\s+", " ").trim();
        Map<String, Object> created = sessionStore.createSession(title, agent.getId(),
                agent.getModelProvider(), agent.getModelName(), request.projectId(), request.directory(), null);
        return String.valueOf(created.get("id"));
    }

    /** 供 SessionController 使用：按 ID 取 Agent（校验存在性） */
    AgentSpec requireAgent(@RequestParam(required = false) String agentId) {
        return resolveAgent(agentId);
    }

    /**
     * 入口调度指令注入（设计文档 56 §5.3）：~/.gewu 全局层 + 会话绑定目录项目层，
     * 跨层裁决产物填充任务字段；无入口文件时保持 null（引擎不注入，行为不变）。
     */
    private void applyEntryDirectives(AgentTask task) {
        try {
            var resolution = entryFileResolver.resolve(task.getWorkspaceRoot());
            if (resolution.present()) {
                task.setEntryDirectives(resolution.content());
                task.setEntryDirectivesSource(resolution.source());
                task.setEntryDirectivesMeta(resolution.metaJson());
            }
        } catch (Exception e) {
            // 解析失败降级为无入口指令，不阻塞会话
        }
    }
}
