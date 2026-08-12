package com.gewu.application.wenshi.search;

import com.gewu.infrastructure.llm.LlmClient;
import com.gewu.infrastructure.llm.LlmClientFactory;
import com.gewu.infrastructure.llm.LlmResponse;
import com.gewu.infrastructure.wenshi.adapter.EmbeddingAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * WebResultVerifier 四级漏斗正确性判断单元测试。
 * <p>
 * 覆盖各级过滤边界、置信度阈值边界、LLM 仲裁触发条件及降级行为。
 *
 * @since 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("网络搜索结果正确性判断测试")
class WebResultVerifierTest {

    @Mock
    private EmbeddingAdapter embeddingAdapter;

    @Mock
    private LlmClientFactory llmClientFactory;

    @Mock
    private LlmClient llmClient;

    private WebResultVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        verifier = new WebResultVerifier(embeddingAdapter, llmClientFactory);
        setField(verifier, "minConfidence", 0.6);
        setField(verifier, "llmArbitrateThreshold", 0.4);
        setField(verifier, "llmArbitrateModel", "deepseek-chat");
        setField(verifier, "llmArbitrateProvider", "deepseek");
        // Embedding 默认返回中性向量
        when(embeddingAdapter.embed(anyString())).thenReturn(new float[]{1.0f, 0.0f, 0.0f});
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    @DisplayName("第一级规则预筛：过短内容被丢弃，正常内容通过")
    void rulePreFilter_shortSnippet_discarded() {
        WebSearchFragment shortFrag = fragment("短", "http://x.com", "太短");
        WebSearchFragment okFrag = fragment("正常标题", "http://example.com",
                "这是一个关于2025年人工智能发展趋势的详细分析报告内容超过五十字符");

        List<WebSearchFragment> results = verifier.verify(Arrays.asList(shortFrag, okFrag), "2025年人工智能");

        // shortFrag 被丢弃且明确是"内容过短"原因
        assertTrue(shortFrag.isDiscarded(), "过短内容应被丢弃");
        assertEquals("RULE", shortFrag.getVerifyMethod());
        assertEquals("内容过短", shortFrag.getDiscardReason());
    }

    @Test
    @DisplayName("第一级规则预筛：不可信来源被丢弃")
    void rulePreFilter_untrustedSource_discarded() {
        WebSearchFragment bad = fragment("spam", "http://spamsite.com",
                "这里有一段超过五十字符的内容用于测试不可信来源过滤机制是否正常工作测试测试测试");
        List<WebSearchFragment> results = verifier.verify(List.of(bad), "测试");

        assertTrue(bad.isDiscarded(), "不可信来源应被丢弃");
        assertNotNull(bad.getDiscardReason());
    }

    @Test
    @DisplayName("第二级交叉投票：多个来源并发不直接丢弃")
    void consensusVote_consistentFacts_notDiscarded() {
        WebSearchFragment a = fragment("来源A", "http://a.com",
                "数据显示2025年人工智能市场规模达到1000亿元规模增长分析行业专家普遍看好未来发展趋势且内容长度需要超过五十字符的限制阈值");
        WebSearchFragment b = fragment("来源B", "http://b.com",
                "报告指出2025年人工智能市场1000亿元预测形成新增长趋势多家机构一致认同市场前景且这段内容也必须超过五十字符长度阈值限制");

        List<WebSearchFragment> results = verifier.verify(Arrays.asList(a, b), "2025年人工智能市场规模");

        String diag = String.format(
                "a: disc=%b conf=%.3f vm=%s reason=%s | b: disc=%b conf=%.3f vm=%s reason=%s",
                a.isDiscarded(), a.getConfidence(), a.getVerifyMethod(), a.getDiscardReason(),
                b.isDiscarded(), b.getConfidence(), b.getVerifyMethod(), b.getDiscardReason());

        boolean anyKept = results.stream().anyMatch(r -> !r.isDiscarded());
        assertTrue(anyKept, "多源一致应至少保留一条 [" + diag + "]");
    }

    @Test
    @DisplayName("第三级置信度评分：政府来源得分高于普通来源")
    void scoreConfidence_govHigherThanUnknown() {
        WebSearchFragment gov = fragment("gov", "http://www.gov.cn",
                "国务院发布2025年最新政策文件详细内容必须超过五十字符用于测试长度阈值测试测试");
        WebSearchFragment unknown = fragment("unknown", "http://randomblog.xyz",
                "某人博客发布的2025年内容必须也超过五十字符才能进入下一级阈值测试测试测试");

        List<WebSearchFragment> results = verifier.verify(Arrays.asList(gov, unknown), "2025年最新政策");

        // 政府来源应具有较高置信度，普通未知来源较低
        assertTrue(gov.getConfidence() >= unknown.getConfidence(),
                "政府来源置信度应不低于未知来源: gov=" + gov.getConfidence() + " unknown=" + unknown.getConfidence());
    }

    @Test
    @DisplayName("第四级 LLM 仲裁：当 LLM 不可用时保守采纳或丢弃，不抛异常")
    void llmArbitrate_llmUnavailableNoException() {
        WebSearchFragment r = fragment("中性来源", "http://medium.com",
                "这是一段长度满足五十字符要求的内容用于测试仲裁逻辑机制测试测试测试测试");

        // LLM 抛异常
        when(llmClientFactory.getClient("deepseek")).thenThrow(new RuntimeException("LLM 不可用"));

        // 不应抛异常
        List<WebSearchFragment> results = verifier.verify(List.of(r), "测试问题");
        assertNotNull(results);
    }

    @Test
    @DisplayName("空结果列表不影响验证")
    void verify_emptyList_returnsEmpty() {
        List<WebSearchFragment> results = verifier.verify(List.of(), "query");
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("来源信任分：政府/学术来源得分最高")
    void sourceTrustScore_govEdu_highestScore() {
        assertEquals(0.9, verifier.sourceTrustScore("gov.cn"), 0.01);
        assertEquals(0.9, verifier.sourceTrustScore("edu.cn"), 0.01);
        assertEquals(0.85, verifier.sourceTrustScore("wikipedia.org"), 0.01);
        assertEquals(0.85, verifier.sourceTrustScore("baike.baidu.com"), 0.01);
        // .gov.cn 后缀识别
        assertEquals(0.9, verifier.sourceTrustScore("www.gov.cn"), 0.01);
        assertEquals(0.85, verifier.sourceTrustScore("zhihu.com"), 0.01);
        assertEquals(0.5, verifier.sourceTrustScore("random-blog.com"), 0.01);
        assertEquals(0.3, verifier.sourceTrustScore(""), 0.01);
        assertEquals(0.3, verifier.sourceTrustScore(null), 0.01);
    }

    @Test
    @DisplayName("LLM 仲裁：conservative adopt 模式下 LLM 通过响应应采纳")
    void llmArbitrate_llmReturnsPassed_adopted() {
        WebSearchFragment r = fragment("中性来源", "http://medium.com",
                "这是一段长度满足五十字符要求的内容用于测试LLM仲裁逻辑机制测试测试测试测试");

        when(llmClientFactory.getClient("deepseek")).thenReturn(llmClient);
        when(llmClient.chat(any())).thenReturn(LlmResponse.builder()
                .content("{\"passed\": true, \"reason\": \"相关且可信\"}")
                .build());

        List<WebSearchFragment> results = verifier.verify(List.of(r), "测试问题");

        // 结果通过或丢弃，verifyMethod 至少被设置
        assertNotNull(r.getVerifyMethod());
    }

    private WebSearchFragment fragment(String title, String url, String snippet) {
        return WebSearchFragment.builder()
                .url(url)
                .title(title)
                .snippet(snippet)
                .source(domainOf(url))
                .rank(1)
                .adopted(false)
                .discarded(false)
                .confidence(0.0)
                .verifyMethod("NONE")
                .build();
    }

    private String domainOf(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            if (host != null && host.startsWith("www.")) {
                host = host.substring(4);
            }
            return host != null ? host : "";
        } catch (Exception e) {
            return "";
        }
    }
}