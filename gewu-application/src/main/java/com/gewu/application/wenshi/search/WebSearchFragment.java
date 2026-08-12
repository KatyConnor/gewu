package com.gewu.application.wenshi.search;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 网络搜索结果条目。
 * <p>
 * 表示从搜索引擎获取的单条搜索结果，包含原始信息及经过正确性判断后的状态标记。
 * 被 {@link WebResultVerifier} 四级漏斗处理，标记 adopted/discarded 状态，
 * 仅 adopted 条目会被注入 LLM system prompt。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebSearchFragment {

    /** 结果页面的 URL */
    private String url;

    /** 结果标题 */
    private String title;

    /** 摘要文本（搜索引擎返回的 content/snippet） */
    private String snippet;

    /** 来源域名（从 url 提取，如 example.com） */
    private String source;

    /** 发布时间（如有，ISO 格式字符串） */
    private String publishedAt;

    /** 搜索排名（1 为最靠前） */
    private int rank;

    /** 是否采纳（通过正确性判断，可注入 LLM） */
    private boolean adopted;

    /** 是否丢弃（未通过正确性判断） */
    private boolean discarded;

    /** 丢弃原因（验证失败时填写） */
    private String discardReason;

    /** 置信度评分 0.0-1.0，由 {@link WebResultVerifier} 综合评分 */
    private double confidence;

    /** 验证方法（RULE / CONSENSUS / SCORE / LLM / PASSTHROUGH） */
    private String verifyMethod;
}