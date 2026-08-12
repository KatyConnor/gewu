package com.gewu.application.wenshi.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.infrastructure.llm.LlmClient;
import com.gewu.infrastructure.llm.LlmClientFactory;
import com.gewu.infrastructure.llm.LlmRequest;
import com.gewu.infrastructure.llm.LlmResponse;
import com.gewu.infrastructure.llm.Message;
import com.gewu.infrastructure.wenshi.adapter.EmbeddingAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网络搜索结果正确性判断器 — 四级漏斗机制。
 * <p>
 * 按成本从低到高依次执行，每一级都可能过滤或标记结果：
 * <ol>
 *   <li>规则预筛：过滤内容过短、来源不可信、与问题无关的结果（0 LLM 调用）</li>
 *   <li>多源交叉投票：多个来源表达相同关键事实则加分，孤立观点则减分（0 LLM 调用）</li>
 *   <li>置信度评分：综合来源可信度 + 多源一致性 + Embedding 相关性打分（0 LLM 调用）</li>
 *   <li>LLM 仲裁：仅对置信度处于灰色地带的结果调用 LLM 做最终判断（多数情况 0 LLM 调用）</li>
 * </ol>
 * <p>
 * 最终每条结果被标记 adopted（采纳）或 discarded（丢弃），仅 adopted 结果注入 LLM 推理上下文。
 * 这种漏斗式设计确保前三级过滤零 LLM token 消耗，仅在无法确定时才触发 LLM。
 *
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebResultVerifier {

    private final EmbeddingAdapter embeddingAdapter;
    private final LlmClientFactory llmClientFactory;

    /** 采纳的最低置信度阈值 */
    @Value("${gewu.wenshi.web-search.min-confidence:0.6}")
    private double minConfidence;

    /** 低于此分才触发 LLM 仲裁 */
    @Value("${gewu.wenshi.web-search.llm-arbitrate-threshold:0.4}")
    private double llmArbitrateThreshold;

    /** LLM 仲裁使用的模型 */
    @Value("${gewu.wenshi.web-search.llm-arbitrate-model:deepseek-chat}")
    private String llmArbitrateModel;

    /** LLM 仲裁使用的提供商 */
    @Value("${gewu.wenshi.web-search.llm-arbitrate-provider:deepseek}")
    private String llmArbitrateProvider;

    /** 内容过短阈值（字符数） */
    private static final int MIN_SNIPPET_LENGTH = 50;

    /** 不可信来源域名（垃圾内容/成人/AI 生成站等） */
    private static final Set<String> UNTRUSTED_DOMAINS = new HashSet<>(Arrays.asList(
            "spamsite.com", "adult-content.com", "lowquality.net",
            "contentfarm.io", "lowquality-blog.com"
    ));

    /** 可信来源域名后缀/政府学术机构（加权加分） */
    private static final Set<String> TRUSTED_TLD = new HashSet<>(Arrays.asList(
            ".gov", ".gov.cn", ".edu", ".edu.cn"
    ));

    /** 可信知名媒体来源 */
    private static final Set<String> TRUSTED_SOURCES = new HashSet<>(Arrays.asList(
            "wikipedia.org", "baike.baidu.com", "zhihu.com",
            "cctv.com", "xinhuanet.com", "people.com.cn",
            "github.com", "stackoverflow.com", "medium.com",
            "arxiv.org", "sciencedirect.com", "nature.com"
    ));

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 执行四级漏斗正确性判断。
     *
     * @param results 搜索结果列表（未经判断）
     * @param query   原始查询，用于相关性判断
     * @return 处理后的结果列表（每条标记 adopted/discarded）
     * @since 1.0.0
     */
    public List<WebSearchFragment> verify(List<WebSearchFragment> results, String query) {
        if (results == null || results.isEmpty()) {
            return results;
        }

        // 第一级：规则预筛
        List<WebSearchFragment> filtered = rulePreFilter(results, query);
        log.debug("WebResultVerifier: rulePreFilter {} -> {}", results.size(), filtered.size());

        if (filtered.isEmpty()) {
            return filtered;
        }

        // 第二级：多源交叉投票
        consensusVote(filtered, query);

        // 第三级：置信度评分
        scoreConfidence(filtered, query);

        // 第四级：LLM 仲裁（仅灰色地带）
        llmArbitrate(filtered, query);

        long adopted = filtered.stream().filter(WebSearchFragment::isAdopted).count();
        long discarded = filtered.stream().filter(WebSearchFragment::isDiscarded).count();
        log.info("WebResultVerifier: total={}, adopted={}, discarded={}", filtered.size(), adopted, discarded);

        return filtered;
    }

    /**
     * 第一级：规则预筛 — 过滤明显劣质结果。
     * <p>
     * 过滤条件：
     * <ul>
     *   <li>摘要为 null 或过短（&lt; 50 字符）</li>
     *   <li>来源在不可信域名黑名单中</li>
     *   <li>摘要与查询无任何关键词重叠</li>
     * </ul>
     * 通过预筛的结果初始 confidence 设为 0.5。
     */
    private List<WebSearchFragment> rulePreFilter(List<WebSearchFragment> results, String query) {
        List<WebSearchFragment> filtered = new ArrayList<>();
        Set<String> queryKeywords = extractKeywords(query);

        for (WebSearchFragment r : results) {
            // 过滤空/短内容
            if (r.getSnippet() == null || r.getSnippet().length() < MIN_SNIPPET_LENGTH) {
                r.setDiscarded(true);
                r.setDiscardReason("内容过短");
                r.setVerifyMethod("RULE");
                continue;
            }

            // 过滤不可信来源
            if (isUntrustedSource(r.getSource())) {
                r.setDiscarded(true);
                r.setDiscardReason("不可信来源: " + r.getSource());
                r.setVerifyMethod("RULE");
                continue;
            }

            // 检查与查询的关键词重叠
            if (!hasQueryOverlap(r.getSnippet(), queryKeywords) && !hasQueryOverlap(r.getTitle(), queryKeywords)) {
                r.setDiscarded(true);
                r.setDiscardReason("与问题无关");
                r.setVerifyMethod("RULE");
                continue;
            }

            // 通过预筛，初始化置信度
            r.setConfidence(0.5);
            r.setVerifyMethod("RULE");
            filtered.add(r);
        }
        return filtered;
    }

    /**
     * 第二级：多源交叉投票 — 多个来源表达相同关键事实则加分。
     * <p>
     * 提取每条结果中的数字和专有名词作为"关键事实"指纹，
     * 多条结果包含相同关键事实则各加 0.2 置信度，孤立观点减 0.05。
     */
    void consensusVote(List<WebSearchFragment> results, String query) {
        if (results.size() <= 1) {
            return;
        }

        // 提取每条结果的关键事实指纹（数字 + 大写词 + 引用词）
        Map<String, List<WebSearchFragment>> factToFragments = new HashMap<>();
        for (WebSearchFragment r : results) {
            Set<String> facts = extractKeyFacts(r.getSnippet());
            r.setConfidence(Math.max(0.0, r.getConfidence() - 0.05)); // 孤立观点默认减分
            for (String fact : facts) {
                factToFragments.computeIfAbsent(fact, k -> new ArrayList<>()).add(r);
            }
        }

        // 被多个来源共享的关键事实 → 相关结果加分
        for (Map.Entry<String, List<WebSearchFragment>> entry : factToFragments.entrySet()) {
            if (entry.getValue().size() >= 2) {
                for (WebSearchFragment r : entry.getValue()) {
                    r.setConfidence(Math.min(1.0, r.getConfidence() + 0.2));
                }
            }
        }

        // 标记验证方法
        for (WebSearchFragment r : results) {
            if ("RULE".equals(r.getVerifyMethod())) {
                r.setVerifyMethod("CONSENSUS");
            }
        }
    }

    /**
     * 第三级：置信度评分 — 综合来源可信度 + 多源一致性 + Embedding 相关性。
     * <p>
     * 公式：confidence = confidence * 0.5 + relevance * 0.3 + sourceScore * 0.2
     * 其中 relevance 为查询与摘要的 Embedding 余弦相似度，sourceScore 为来源域名信任分。
     * 此级不调用 LLM，仅复用 EmbeddingAdapter。
     */
    void scoreConfidence(List<WebSearchFragment> results, String query) {
        float[] queryVec;
        try {
            queryVec = embeddingAdapter.embed(query);
        } catch (Exception e) {
            log.debug("WebResultVerifier: embedding query failed, skip relevance scoring: {}", e.getMessage());
            // Embedding 不可用时仅用来源可信度和已有 confidence
            for (WebSearchFragment r : results) {
                r.setConfidence(Math.min(1.0, r.getConfidence() * 0.7 + sourceTrustScore(r.getSource()) * 0.3));
                if ("RULE".equals(r.getVerifyMethod()) || "CONSENSUS".equals(r.getVerifyMethod())) {
                    r.setVerifyMethod("SCORE");
                }
            }
            return;
        }

        for (WebSearchFragment r : results) {
            double relevance = 0.0;
            try {
                float[] snippetVec = embeddingAdapter.embed(r.getSnippet());
                relevance = cosineSimilarity(queryVec, snippetVec);
            } catch (Exception e) {
                log.debug("WebResultVerifier: embedding snippet failed: {}", e.getMessage());
                relevance = 0.5; // 未知相关性时给中性分
            }

            double sourceScore = sourceTrustScore(r.getSource());
            // 综合打分
            r.setConfidence(r.getConfidence() * 0.5 + relevance * 0.3 + sourceScore * 0.2);
            if ("RULE".equals(r.getVerifyMethod()) || "CONSENSUS".equals(r.getVerifyMethod())) {
                r.setVerifyMethod("SCORE");
            }
        }
    }

    /**
     * 第四级：LLM 仲裁 — 仅对置信度处于灰色地带的结果调用 LLM。
     * <p>
     * 区分逻辑：
     * <ul>
     *   <li>confidence >= minConfidence（0.6）：直接采纳，跳过 LLM</li>
     *   <li>llmArbitrateThreshold <= confidence < minConfidence：调用 LLM 仲裁</li>
     *   <li>confidence < llmArbitrateThreshold（0.4）：直接丢弃</li>
     * </ul>
     * 大多数情况下此级不会触发 LLM 调用。
     */
    void llmArbitrate(List<WebSearchFragment> results, String query) {
        for (WebSearchFragment r : results) {
            // 高置信度直接采纳
            if (r.getConfidence() >= minConfidence) {
                r.setAdopted(true);
                r.setVerifyMethod("PASSTHROUGH");
                continue;
            }

            // 极低置信度直接丢弃
            if (r.getConfidence() < llmArbitrateThreshold) {
                r.setDiscarded(true);
                r.setDiscardReason("置信度过低: " + String.format("%.2f", r.getConfidence()));
                r.setVerifyMethod("SCORE");
                continue;
            }

            // 灰色地带：调用 LLM 仲裁
            boolean llmPassed = callLlmArbitration(query, r);
            if (llmPassed) {
                r.setAdopted(true);
                r.setVerifyMethod("LLM");
            } else {
                r.setDiscarded(true);
                r.setDiscardReason("LLM 仲裁未通过");
                r.setVerifyMethod("LLM");
            }
        }
    }

    /**
     * 调用 LLM 对单条搜索结果做正确性仲裁。
     * <p>
     * 复用 Critic.tryLlmSelfCheck 的轻量模式：截取前 500 字符，
     * LLM 只回答 JSON {passed, reason}。
     *
     * @param query   原始查询
     * @param fragment 待仲裁结果
     * @return LLM 判定 true 表示可信
     */
    private boolean callLlmArbitration(String query, WebSearchFragment fragment) {
        try {
            LlmClient client = llmClientFactory.getClient(llmArbitrateProvider);
            String snippet = fragment.getSnippet();
            if (snippet.length() > 500) {
                snippet = snippet.substring(0, 500);
            }

            LlmRequest request = LlmRequest.builder()
                    .model(llmArbitrateModel)
                    .messages(List.of(
                            Message.builder().role("system").content(
                                    "你是信息正确性判断器。判断以下搜索结果是否可信且与问题相关。" +
                                    "只回答 JSON：{\"passed\": true/false, \"reason\": \"简短说明\"}")
                                    .build(),
                            Message.builder().role("user").content(
                                    "问题：" + query + "\n\n" +
                                    "搜索结果标题：" + fragment.getTitle() + "\n" +
                                    "搜索内容：" + snippet + "\n" +
                                    "来源：" + fragment.getSource())
                                    .build()
                    ))
                    .stream(false)
                    .build();

            LlmResponse response = client.chat(request);
            String content = response.getContent();
            if (content != null && content.contains("\"passed\"")) {
                // 简单解析，兼容力关键 LLM 回复可能带有 ```json 等标记
                return content.contains("true");
            }
            // 无法解析时保守采纳
            log.debug("WebResultVerifier: LLM arbitration unparseable, conservative adopt");
            return true;
        } catch (Exception e) {
            log.debug("WebResultVerifier: LLM arbitration failed, conservative pass: {}", e.getMessage());
            // LLM 不可用时保守采纳，避免过度过滤
            return true;
        }
    }

    /**
     * 判断来源是否在不可信黑名单中。
     */
    private boolean isUntrustedSource(String source) {
        if (source == null || source.isBlank()) {
            return false;
        }
        String lower = source.toLowerCase();
        return UNTRUSTED_DOMAINS.contains(lower);
    }

    /**
     * 计算来源域名信任分 0.0-1.0。
     * <p>
     * 可信来源（政府/学术/知名媒体）返回高分，普通来源返回 0.5。
     */
    double sourceTrustScore(String source) {
        if (source == null || source.isBlank()) {
            return 0.3;
        }
        String lower = source.toLowerCase();

        // 可信 TLD：支持 gov.cn / .gov.cn 两种形式（带或不带前导点）
        for (String tld : TRUSTED_TLD) {
            String bare = tld.startsWith(".") ? tld.substring(1) : tld;
            if (lower.endsWith(tld) || lower.equals(bare) || lower.endsWith("." + bare)) {
                return 0.9;
            }
        }

        // 知名媒体
        for (String trusted : TRUSTED_SOURCES) {
            if (lower.contains(trusted)) {
                return 0.85;
            }
        }

        return 0.5;
    }

    /**
     * 检查文本与查询关键词是否至少有一个重叠词。
     */
    private boolean hasQueryOverlap(String text, Set<String> queryKeywords) {
        if (text == null || text.isEmpty() || queryKeywords == null || queryKeywords.isEmpty()) {
            return true; // 无关键词可对比时不过滤
        }
        String lowerText = text.toLowerCase();
        for (String kw : queryKeywords) {
            if (lowerText.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 提取查询中的关键词（去停用词、按空格分词 + 中文双字分词降级）。
     */
    private Set<String> extractKeywords(String query) {
        Set<String> keywords = new HashSet<>();
        if (query == null) {
            return keywords;
        }
        String lower = query.toLowerCase();
        // 英文/数字连续词
        Matcher m = Pattern.compile("[a-z0-9]{3,}").matcher(lower);
        while (m.find()) {
            keywords.add(m.group());
        }
        // 中文两字分组（粗粒度近似分词）
        for (int i = 0; i + 2 <= query.length(); i++) {
            String pair = lower.substring(i, i + 2);
            if (isChinesePair(pair)) {
                keywords.add(pair);
            }
        }
        return keywords;
    }

    private boolean isChinesePair(String s) {
        for (char c : s.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fff') {
                return true;
            }
        }
        return false;
    }

    /**
     * 从搜索结果摘要中提取关键事实指纹。
     * <p>
     * 提取数字（含百分比/年份）和以双引号包裹的引用词，
     * 作为多源交叉验证的事实标记。
     */
    private Set<String> extractKeyFacts(String text) {
        Set<String> facts = new HashSet<>();
        if (text == null) {
            return facts;
        }
        // 数字（含年份）
        Matcher numM = Pattern.compile("\\d+\\.?\\d*%?").matcher(text);
        while (numM.find()) {
            facts.add("NUM:" + numM.group());
        }
        // 引号引用
        Matcher quoteM = Pattern.compile("\"[^\"]{3,}\"").matcher(text);
        while (quoteM.find()) {
            facts.add("QUOTE:" + quoteM.group());
        }
        return facts;
    }

    /**
     * 计算两个向量的余弦相似度。
     */
    private double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0.0;
        }
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}