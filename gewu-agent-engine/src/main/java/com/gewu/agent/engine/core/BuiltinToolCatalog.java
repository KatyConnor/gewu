package com.gewu.agent.engine.core;

/**
 * 引擎内置工具目录（AE-P2-1 内核拆分第一期）：plan_task / ask_user / 文件四件套的
 * 名称、描述与 JSON Schema 集中定义——供 {@link ReactAgentExecutor} 构建工具清单与
 * 内置分发判断使用（静态导入，调用点零变更）。
 * <p>语义不变量（拆分时保持）：内置工具由执行器<b>直接分发执行</b>，不经过 ToolExecutor
 * 五段安全管线——其操作对象是会话工作空间、用户交互与子代理派生等引擎内受控能力。
 *
 * @author 牛牛
 * @version 1.0.0
 * @since 2026-11-13
 */
public final class BuiltinToolCatalog {

    private BuiltinToolCatalog() {
    }

    // ==================== plan_task（S9 F5）：本地执行，不走 ToolExecutor 外部链路 ====================

    public static final String PLAN_TOOL_NAME = "plan_task";
    public static final String PLAN_TOOL_DESCRIPTION =
            "创建或更新当前任务的任务清单。开始多步骤工作前调用一次以制定计划；"
                    + "完成某步骤或计划变化时再次调用以更新整体状态（全量覆盖语义）。"
                    + "提交方案/计划时：以 markdown 参数提供完整计划正文（引擎保存到工作空间 plan/ 目录供用户查看），"
                    + "随后必须调用 ask_user 等待用户批准，批准后方可开始实施。";
    public static final String PLAN_TOOL_SCHEMA = """
            {"type":"object","properties":{"title":{"type":"string","description":"任务清单标题"},"steps":{"type":"array","description":"任务步骤列表（全量提交，以本次调用为准整体覆盖）","items":{"type":"object","properties":{"id":{"type":"string","description":"步骤唯一标识"},"text":{"type":"string","description":"步骤内容"},"status":{"type":"string","enum":["pending","in_progress","done"],"description":"步骤状态"}},"required":["id","text","status"]}},"markdown":{"type":"string","description":"完整计划正文（Markdown）。提交方案/计划时提供，引擎将保存到工作空间 plan/ 目录供用户查看完整计划"}},"required":["steps"]}
            """;

    // ==================== ask_user（HITL）：向用户提问并挂起等待回答，回答作为工具结果回灌续跑 ====================

    public static final String ASK_TOOL_NAME = "ask_user";
    public static final String ASK_TOOL_DESCRIPTION =
            "向用户提问以获取决策确认（执行会挂起，等待用户在界面上选择或输入回答后继续）。"
                    + "适用：方案/计划需要用户批准、存在歧义需要用户决策、缺少关键信息。"
                    + "提交方案/计划（plan_task）后必须调用本工具等待用户批准，批准后方可开始实施。"
                    + "options 提供 1-4 个候选项可降低用户输入成本，用户也可自行输入回答。";
    public static final String ASK_TOOL_SCHEMA = """
            {"type":"object","properties":{"question":{"type":"string","description":"要向用户确认的问题（简洁明确）"},"options":{"type":"array","items":{"type":"string"},"maxItems":4,"description":"候选项（用户可选中或自行输入回答）"}},"required":["question"]}
            """;

    // ==================== 文件四件套（S9 F3）：经 FileWorkspaceSpi 在会话工作空间执行 ====================

    public static final String READ_FILE_TOOL = "read_file";
    public static final String WRITE_FILE_TOOL = "write_file";
    public static final String EDIT_FILE_TOOL = "edit_file";
    public static final String LIST_DIR_TOOL = "list_dir";
    public static final String READ_FILE_SCHEMA = """
            {"type":"object","properties":{"path":{"type":"string","description":"文件相对路径，相对当前工作空间根目录（如 src/main.go、docs/plan.md）。不要以 / 开头，不要加 workspace/ 前缀"}},"required":["path"]}
            """;
    public static final String WRITE_FILE_SCHEMA = """
            {"type":"object","properties":{"path":{"type":"string","description":"文件相对路径，相对当前工作空间根目录。不要以 / 开头，不要加 workspace/ 前缀"},"content":{"type":"string","description":"完整文件内容（整体覆盖）"}},"required":["path","content"]}
            """;
    public static final String EDIT_FILE_SCHEMA = """
            {"type":"object","properties":{"path":{"type":"string","description":"文件相对路径，相对当前工作空间根目录。不要以 / 开头，不要加 workspace/ 前缀"},"old_text":{"type":"string","description":"要替换的原文（需唯一匹配）"},"new_text":{"type":"string","description":"替换后的新文本"}},"required":["path","old_text","new_text"]}
            """;
    public static final String LIST_DIR_SCHEMA = """
            {"type":"object","properties":{"path":{"type":"string","description":"目录相对路径，相对当前工作空间根目录。可省略（默认列出根目录）。不要以 / 开头，不要加 workspace/ 前缀"}},"required":[]}
            """;
}
