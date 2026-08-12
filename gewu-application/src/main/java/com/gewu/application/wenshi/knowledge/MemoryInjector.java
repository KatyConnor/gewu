package com.gewu.application.wenshi.knowledge;

import com.gewu.application.wenshi.search.WebSearchFragment;
import com.gewu.domain.wenshi.knowledge.EpisodicEvent;
import com.gewu.domain.wenshi.knowledge.ProceduralMemory;
import com.gewu.domain.wenshi.knowledge.SemanticFragment;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * 记忆注入器 — 将路由结果中的记忆数据转换为 LLM Prompt 可消费的文本摘要。
 * <p>
 * 注入策略：
 * <ul>
 *   <li>语义记忆 → "## 相关知识" 段落</li>
 *   <li>程序记忆 → "## 操作流程" 段落</li>
 *   <li>情景记忆 → "## 历史记录" 段落</li>
 * </ul>
 * 每条记忆内容会被截断到 {@code summaryLength} 长度，整体 token 估算基于字符数 / 4。
 * 支持通过 {@link #expandMemory} 按需展开某条记忆的原始内容。
 *
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryInjector {

    /** 注入摘要的最大 token 上限，超过时需调用方截断。 */
    @Value("${wenshi.injection.max-tokens:2000}")
    private int maxTokens;

    /** 单条记忆摘要的最大字符长度。 */
    @Value("${wenshi.injection.summary-length:100}")
    private int summaryLength;

    /** 单条网络搜索结果摘要的最大字符长度。 */
    @Value("${gewu.wenshi.web-search.snippet-max-length:200}")
    private int webSummaryLength;

    /**
     * 根据路由结果准备记忆注入计划。
     * <p>
     * 将各类记忆转换为 Markdown 格式的摘要文本，同时保留完整记忆引用以便按需展开。
     *
     * @param routing 路由结果，包含各类型记忆列表
     * @return 注入计划，包含摘要文本、完整记忆引用和 token 估算值
     * @since 1.0.0
     */
    public InjectionPlan prepareInjection(MemoryRouter.RoutingResult routing) {
        InjectionPlan plan = new InjectionPlan();
        StringBuilder summary = new StringBuilder();

        if (routing.getSemanticMemories() != null && !routing.getSemanticMemories().isEmpty()) {
            summary.append("## 相关知识\n");
            for (SemanticFragment fragment : routing.getSemanticMemories()) {
                String truncated = truncate(fragment.getContent(), summaryLength);
                summary.append("- ").append(truncated).append("\n");
            }
        }

        if (routing.getProceduralMemories() != null && !routing.getProceduralMemories().isEmpty()) {
            summary.append("## 操作流程\n");
            for (ProceduralMemory memory : routing.getProceduralMemories()) {
                // 优先使用 description，缺失时回退到 name
                String desc = memory.getDescription() != null ? memory.getDescription() : memory.getName();
                summary.append("- ").append(memory.getName()).append(": ").append(truncate(desc, summaryLength)).append("\n");
            }
        }

        if (routing.getEpisodicMemories() != null && !routing.getEpisodicMemories().isEmpty()) {
            summary.append("## 历史记录\n");
            for (EpisodicEvent event : routing.getEpisodicMemories()) {
                summary.append("- ").append(truncate(event.getContent(), summaryLength)).append("\n");
            }
        }

        // 网络搜索结果：仅注入采纳结果（已通过四级漏斗正确性判断）
        if (routing.getWebSearchResults() != null && !routing.getWebSearchResults().isEmpty()) {
            summary.append("## 网络检索\n");
            for (WebSearchFragment fragment : routing.getWebSearchResults()) {
                if (fragment.isAdopted() && !fragment.isDiscarded()) {
                    String truncated = truncate(fragment.getSnippet(), webSummaryLength);
                    summary.append("- [").append(fragment.getSource()).append("] ")
                            .append(truncated)
                            .append(" (置信度: ").append(formatConfidence(fragment.getConfidence())).append(")\n");
                }
            }
        }

        plan.setSummary(summary.toString());
        plan.setFullMemories(routing);
        // 粗略估算 token 数：中文约每 4 字符 = 1 token
        plan.setTokenEstimate(summary.length() / 4);

        return plan;
    }

    /**
     * 根据记忆 ID 展开某条记忆的完整内容。
     * <p>
     * 在语义记忆和情景记忆中按 ID 查找，返回原始内容（未截断）。
     * 用于用户在摘要中点击"查看详情"的场景。
     *
     * @param memoryId      记忆片段 ID
     * @param fullMemories  完整记忆路由结果
     * @return 原始内容字符串，未找到返回 null
     * @since 1.0.0
     */
    public String expandMemory(String memoryId, MemoryRouter.RoutingResult fullMemories) {
        if (fullMemories.getSemanticMemories() != null) {
            for (SemanticFragment fragment : fullMemories.getSemanticMemories()) {
                if (fragment.getId().equals(memoryId)) {
                    return fragment.getContent();
                }
            }
        }
        if (fullMemories.getEpisodicMemories() != null) {
            for (EpisodicEvent event : fullMemories.getEpisodicMemories()) {
                if (event.getId().equals(memoryId)) {
                    return event.getContent();
                }
            }
        }
        return null;
    }

    /**
     * 将文本截断到指定最大长度，超出部分以 "..." 结尾。
     *
     * @param text      原始文本
     * @param maxLength 最大字符数
     * @return 截断后的文本，null 输入返回空字符串
     */
    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    /**
     * 格式化置信度为百分比字符串。
     */
    private String formatConfidence(double confidence) {
        return String.format("%.0f%%", confidence * 100);
    }

    /**
     * 注入计划 — 封装摘要文本与完整记忆引用。
     * <p>
     * {@code summary} 直接注入 LLM Prompt，{@code fullMemories} 保留用于按需展开。
     *
     * @since 1.0.0
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class InjectionPlan {
        /** Markdown 格式的记忆摘要文本。 */
        private String summary;
        /** 完整记忆路由结果，用于按需展开。 */
        private MemoryRouter.RoutingResult fullMemories;
        /** 估算的 token 数（字符数 / 4）。 */
        private int tokenEstimate;
    }
}
