package com.gewu.application.workflow.engine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点处理器注册表（51 号 §一原则二）。
 * <p>收集全部 {@link WorkflowNodeHandler} Spring Bean，按 type 键索引；
 * 未注册类型回落 {@link #UNKNOWN}（激活即失败：未知节点类型），
 * 保证结构性节点类型缺失时行为可预期。
 *
 * @since 1.0.0
 */
@Slf4j
@Component
public class WorkflowNodeHandlerRegistry {

    /** 未知类型的占位 Handler：激活即失败并带可读原因 */
    static final WorkflowNodeHandler UNKNOWN = new WorkflowNodeHandler() {
        @Override public String type() {
            return "__unknown__";
        }

        @Override public NodeKind kind() {
            return NodeKind.AUTO;
        }

        @Override public void activate(WorkflowNodeContext context) {
            context.complete(false, "未知节点类型，无注册处理器: " + context.node().getNodeType());
        }
    };

    private final Map<String, WorkflowNodeHandler> handlers = new LinkedHashMap<>();
    /** 存量类型别名（start→manual-trigger / end→return 语义映射，51 号 §十三兼容策略） */
    private static final Map<String, String> LEGACY_ALIASES = Map.of(
            "start", "manual-trigger",
            "end", "return",
            "subprocess", "sub-workflow");

    public WorkflowNodeHandlerRegistry(List<WorkflowNodeHandler> registered) {
        for (WorkflowNodeHandler handler : registered) {
            handlers.put(handler.type(), handler);
        }
        log.info("工作流节点注册表: {} 类处理器已注册 {}", handlers.size(), handlers.keySet());
    }

    /** 按节点类型解析 Handler（含存量别名映射；未注册返回 UNKNOWN 占位） */
    public WorkflowNodeHandler resolve(String nodeType) {
        if (nodeType == null) {
            return UNKNOWN;
        }
        WorkflowNodeHandler handler = handlers.get(nodeType);
        if (handler != null) {
            return handler;
        }
        String alias = LEGACY_ALIASES.get(nodeType);
        return alias != null ? handlers.getOrDefault(alias, UNKNOWN) : UNKNOWN;
    }

    /** 是否已注册（校验器用：区分"未知类型"与"有注册但配置缺失"） */
    public boolean isRegistered(String nodeType) {
        return handlers.containsKey(nodeType)
                || LEGACY_ALIASES.containsKey(nodeType) && handlers.containsKey(LEGACY_ALIASES.get(nodeType));
    }

    /** 全部已注册类型（校验器提示用） */
    public java.util.Set<String> registeredTypes() {
        return java.util.Collections.unmodifiableSet(handlers.keySet());
    }
}
