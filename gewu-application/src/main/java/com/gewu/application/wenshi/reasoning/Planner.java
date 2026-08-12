package com.gewu.application.wenshi.reasoning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.application.ai.ModelConfigService;
import com.gewu.infrastructure.llm.LlmClient;
import com.gewu.infrastructure.llm.LlmClientFactory;
import com.gewu.infrastructure.llm.LlmRequest;
import com.gewu.infrastructure.llm.LlmResponse;
import com.gewu.infrastructure.llm.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 任务规划器 - 将复杂任务分解为可执行的子目标序列。
 * <p>
 * 采用模板匹配优先、LLM 分解兜底的两阶段策略：
 * <ol>
 *   <li>模板匹配：基于关键词快速匹配预定义的任务模板（查询/分析/执行）</li>
 *   <li>LLM 分解：当无匹配模板时，调用 LLM 生成多子目标分解计划</li>
 * </ol>
 * 模板匹配避免了不必要的 LLM 调用，降低延迟和成本。
 *
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Planner {

    private final LlmClientFactory llmClientFactory;
    private final ModelConfigService modelConfigService;

    @Value("${gewu.wenshi.llm.default-provider:qwen}")
    private String defaultLlmProvider;

    @Value("${gewu.wenshi.llm.default-model:qwen-plus}")
    private String defaultLlmModel;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 执行任务规划。
     * <p>
     * 优先尝试模板匹配，若无法匹配则退化为 LLM 分解。
     *
     * @param task 用户输入的任务描述；不可为 null
     * @param request 完整的推理请求，提供上下文信息
     * @return 执行计划树，包含分解后的子目标列表
     * @since 1.0.0
     */
    public WenshiReasoningResult.PlanTree plan(String task, WenshiReasoningRequest request) {
        // 优先使用模板匹配，避免不必要的 LLM 调用
        List<WenshiReasoningResult.SubgoalNode> subgoals = tryTemplateMatch(task);

        if (subgoals == null || subgoals.isEmpty()) {
            // 无匹配模板时退化为 LLM 分解
            subgoals = decomposeViaLlm(task, request.getModel());
        }

        return WenshiReasoningResult.PlanTree.builder()
                .task(task)
                .subgoals(subgoals)
                .build();
    }

    /**
     * 尝试基于关键词模板匹配任务分解。
     * <p>
     * 支持三类预定义任务模式：
     * <ul>
     *   <li>查询类：理解需求 → 执行查询 → 格式化输出</li>
     *   <li>分析类：获取数据 → 执行分析 → 生成报告</li>
     *   <li>执行类：确认参数 → 执行操作</li>
     * </ul>
     *
     * @param task 用户任务描述
     * @return 匹配成功的子目标列表，无匹配时返回 null
     * @since 1.0.0
     */
    private List<WenshiReasoningResult.SubgoalNode> tryTemplateMatch(String task) {
        String lowerTask = task.toLowerCase();

        // 文档/报告/Word 生成模板：复杂内容输出到文件
        if (lowerTask.contains("写文档") || lowerTask.contains("生成文档") || lowerTask.contains("写报告")
                || lowerTask.contains("生成报告") || lowerTask.contains("word") || lowerTask.contains("docx")
                || lowerTask.contains("word文档") || lowerTask.contains("写一个文档")
                || lowerTask.contains("技术方案") || lowerTask.contains("设计文档")
                || lowerTask.contains("需求文档") || lowerTask.contains("操作手册")
                || lowerTask.contains("开发实现") || lowerTask.contains("用java")
                || lowerTask.contains("用python") || lowerTask.contains("用go")
                || lowerTask.contains("用typescript") || lowerTask.contains("服务应用")
                || lowerTask.contains("服务端") || lowerTask.contains("服务系统")) {
            // 单一 FILE_OUTPUT 子目标，描述保留完整用户原始任务（含所有技术约束）
            return List.of(
                    subgoal("subgoal-1", task, "FILE_OUTPUT", List.of())
            );
        }

        // 时效性/事实性问题模板：需要网络搜索获取最新信息
        if (lowerTask.contains("最新") || lowerTask.contains("新闻") || lowerTask.contains("时事")
                || lowerTask.contains("今天") || lowerTask.contains("2025") || lowerTask.contains("2026")
                || lowerTask.contains("目前") || lowerTask.contains("现在") || lowerTask.contains("近期")
                || lowerTask.contains("当前") || lowerTask.contains("实时")) {
            return List.of(
                    subgoal("subgoal-1", "搜索相关最新信息", "WEB_SEARCH", List.of()),
                    subgoal("subgoal-2", "综合搜索结果生成回答", "LLM_REASONING", List.of("subgoal-1"))
            );
        }

        if (lowerTask.contains("查") || lowerTask.contains("查询") || lowerTask.contains("search")) {
            return List.of(
                    subgoal("subgoal-1", "理解查询需求", "KNOWLEDGE_LOOKUP", List.of()),
                    subgoal("subgoal-2", "执行数据查询", "TOOL_EXECUTION", List.of("subgoal-1")),
                    subgoal("subgoal-3", "格式化输出结果", "TOOL_EXECUTION", List.of("subgoal-2"))
            );
        }

        if (lowerTask.contains("分析") || lowerTask.contains("汇总") || lowerTask.contains("统计")) {
            return List.of(
                    subgoal("subgoal-1", "获取待分析数据", "TOOL_EXECUTION", List.of()),
                    subgoal("subgoal-2", "执行数据分析", "TOOL_EXECUTION", List.of("subgoal-1")),
                    subgoal("subgoal-3", "生成分析报告", "LLM_REASONING", List.of("subgoal-2"))
            );
        }

        if (lowerTask.contains("执行") || lowerTask.contains("操作") || lowerTask.contains("处理")) {
            return List.of(
                    subgoal("subgoal-1", "确认操作参数", "KNOWLEDGE_LOOKUP", List.of()),
                    subgoal("subgoal-2", "执行操作", "TOOL_EXECUTION", List.of("subgoal-1"))
            );
        }

        return null;
    }

    /**
     * 通过 LLM 进行任务分解（兜底策略）。
     * <p>
     * 当模板匹配失败时，调用 LLM 将任务分解为 2-4 个子目标，
     * 每个子目标标注策略类型（KNOWLEDGE_LOOKUP / TOOL_EXECUTION / LLM_REASONING）。
     * LLM 返回 JSON 数组格式，解析失败时回退为单子目标。
     *
     * @param task 用户任务描述
     * @return 子目标列表
     * @since 1.0.0
     */
    private List<WenshiReasoningResult.SubgoalNode> decomposeViaLlm(String task, String model) {
        log.debug("Planner.decomposeViaLlm: task={}", task);
        try {
            String[] pm = resolveProviderAndModel(model);
            String systemPrompt = "你是任务分解专家。将用户任务分解为2-4个子目标，每个子目标标注策略类型。" +
                    "策略类型：KNOWLEDGE_LOOKUP（知识检索）、WEB_SEARCH（网络搜索，适合时效性/事实性问题）、" +
                    "TOOL_EXECUTION（工具执行）、FILE_OUTPUT（生成文件，适合代码/文档/报告等复杂内容）、" +
                    "LLM_REASONING（LLM推理）。\n" +
                    "重要要求：每个子目标的描述必须包含用户原始问题中的所有技术约束（如编程语言、框架版本、技术栈等），" +
                    "不能丢失任何约束信息。例如用户要求'用Java语言基于JDK21'，每个子目标描述都必须包含'Java/JDK21'。\n" +
                    "以JSON数组返回，格式：[{\"description\":\"子目标描述\",\"strategy\":\"策略类型\"}]";

            LlmRequest llmRequest = LlmRequest.builder()
                    .model(pm[1])
                    .messages(List.of(
                            Message.builder().role("system").content(systemPrompt).build(),
                            Message.builder().role("user").content(task).build()))
                    .temperature(0.3)
                    .maxTokens(1024)
                    .stream(false)
                    .build();

            LlmClient client = llmClientFactory.getClient(pm[0]);
            LlmResponse response = client.chat(llmRequest);
            List<WenshiReasoningResult.SubgoalNode> subgoals = parseSubgoals(response.getContent());
            if (subgoals != null && !subgoals.isEmpty()) {
                log.info("Planner.decomposeViaLlm: decomposed into {} subgoals", subgoals.size());
                return subgoals;
            }
        } catch (Exception e) {
            log.warn("Planner.decomposeViaLlm: LLM decomposition failed, falling back to single subgoal: {}", e.getMessage());
        }
        // 回退：单子目标
        return List.of(subgoal("subgoal-1", task, "LLM_REASONING", List.of()));
    }

    /**
     * 解析 LLM 返回的子目标 JSON 数组。
     * <p>
     * 容错处理：提取 JSON 数组部分（处理 LLM 可能返回的额外文本），
     * 验证策略类型合法性，为每个子目标生成 ID 和空依赖列表。
     *
     * @param json LLM 返回的文本
     * @return 解析后的子目标列表，解析失败返回 null
     */
    private List<WenshiReasoningResult.SubgoalNode> parseSubgoals(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            // 提取 JSON 数组部分（LLM 可能返回额外文本）
            int start = json.indexOf('[');
            int end = json.lastIndexOf(']');
            if (start < 0 || end < 0 || end <= start) {
                return null;
            }
            String jsonArray = json.substring(start, end + 1);
            JsonNode root = OBJECT_MAPPER.readTree(jsonArray);
            if (!root.isArray()) {
                return null;
            }
            List<WenshiReasoningResult.SubgoalNode> subgoals = new ArrayList<>();
            for (int i = 0; i < root.size() && i < 6; i++) { // 最多 6 个子目标
                JsonNode node = root.get(i);
                String description = node.has("description") ? node.get("description").asText() : null;
                String strategy = node.has("strategy") ? node.get("strategy").asText() : "LLM_REASONING";
                if (description == null || description.isBlank()) {
                    continue;
                }
                // 验证策略类型
                if (!"KNOWLEDGE_LOOKUP".equals(strategy) && !"WEB_SEARCH".equals(strategy)
                        && !"TOOL_EXECUTION".equals(strategy) && !"FILE_OUTPUT".equals(strategy)
                        && !"LLM_REASONING".equals(strategy)) {
                    strategy = "LLM_REASONING";
                }
                subgoals.add(subgoal("subgoal-" + (i + 1), description, strategy, List.of()));
            }
            return subgoals;
        } catch (Exception e) {
            log.debug("Planner.parseSubgoals: parse failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 构建子目标节点。
     *
     * @param id 子目标唯一标识
     * @param desc 子目标描述
     * @param strategy 推荐求解策略
     * @param deps 前置依赖的子目标 ID 列表
     * @return 子目标节点实例
     * @since 1.0.0
     */
    private WenshiReasoningResult.SubgoalNode subgoal(String id, String desc, String strategy, List<String> deps) {
        return WenshiReasoningResult.SubgoalNode.builder()
                .id(id)
                .description(desc)
                .strategy(strategy)
                .dependencies(deps)
                .completed(false)
                .build();
    }

    /**
     * 根据模型 ID 解析 LLM 提供商和模型名称。
     * <p>
     * 优先从数据库 model_config 表查询 provider_code，
     * 查不到时回退到默认配置。
     *
     * @param model 前端传入的模型 ID
     * @return [providerCode, modelName] 数组
     */
    private String[] resolveProviderAndModel(String model) {
        if (model != null && !model.isBlank()) {
            String providerCode = modelConfigService.getProviderCodeByModelId(model);
            if (providerCode != null) {
                return new String[]{providerCode, model};
            }
        }
        return new String[]{defaultLlmProvider, defaultLlmModel};
    }
}
