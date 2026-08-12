package com.gewu.application.wenshi.reasoning;

/**
 * 任务类型枚举。
 * <p>
 * 定义推理引擎支持的任务分类，用于记忆路由和策略选择。
 * 通过 {@link #classify(String)} 方法基于关键词匹配自动分类。
 * <p>
 * 分类规则：
 * <ul>
 *   <li>DATA_QUERY：包含"查"/"查询"/"search"</li>
 *   <li>DATA_ANALYSIS：包含"分析"/"汇总"/"统计"</li>
 *   <li>PROCESS_EXECUTION：包含"执行"/"操作"/"处理"</li>
 *   <li>DEFAULT：无法匹配以上规则时的默认类型</li>
 * </ul>
 *
 * @since 1.0.0
 */
public enum TaskType {

    /** 数据查询类任务 */
    DATA_QUERY("DATA_QUERY"),

    /** 数据分析类任务 */
    DATA_ANALYSIS("DATA_ANALYSIS"),

    /** 流程执行类任务 */
    PROCESS_EXECUTION("PROCESS_EXECUTION"),

    /** 知识问答类任务 */
    KNOWLEDGE_QA("KNOWLEDGE_QA"),

    /** 默认任务类型（无法分类时兜底） */
    DEFAULT("DEFAULT");

    /** 类型编码，用于持久化和跨系统传递 */
    private final String code;

    /**
     * 构造任务类型枚举。
     *
     * @param code 类型编码
     * @since 1.0.0
     */
    TaskType(String code) {
        this.code = code;
    }

    /**
     * 获取类型编码。
     *
     * @return 类型编码字符串
     * @since 1.0.0
     */
    public String getCode() {
        return code;
    }

    /**
     * 基于消息内容自动分类任务类型。
     * <p>
     * 使用关键词匹配策略，按优先级依次检查各类型的特征词。
     * 匹配顺序：DATA_QUERY → DATA_ANALYSIS → PROCESS_EXECUTION → DEFAULT。
     *
     * @param message 用户输入消息；为 null 时返回 DEFAULT
     * @return 匹配的任务类型，不会返回 null
     * @since 1.0.0
     */
    public static TaskType classify(String message) {
        if (message == null) return DEFAULT;
        String lower = message.toLowerCase();
        if (lower.contains("查") || lower.contains("查询") || lower.contains("search")) {
            return DATA_QUERY;
        }
        if (lower.contains("分析") || lower.contains("汇总") || lower.contains("统计")) {
            return DATA_ANALYSIS;
        }
        if (lower.contains("执行") || lower.contains("操作") || lower.contains("处理")) {
            return PROCESS_EXECUTION;
        }
        return DEFAULT;
    }
}
