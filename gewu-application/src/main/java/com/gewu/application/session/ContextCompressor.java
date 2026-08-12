package com.gewu.application.session;

import com.gewu.infrastructure.cache.CacheService;
import com.gewu.infrastructure.llm.LlmClient;
import com.gewu.infrastructure.llm.LlmClientFactory;
import com.gewu.infrastructure.llm.LlmRequest;
import com.gewu.infrastructure.llm.LlmResponse;
import com.gewu.infrastructure.llm.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 上下文压缩器 - 将超长对话历史压缩为简洁摘要。
 * <p>
 * 支持两种压缩模式（通过 gewu.session.context-compress-mode 配置）：
 * <ul>
 *   <li>llm（默认）：调用 LLM 生成语义摘要，保留关键实体、数据、决策和未解决问题</li>
 *   <li>truncate：纯截断，保留最近的消息直至达到长度上限</li>
 * </ul>
 * LLM 模式下对摘要结果进行缓存（基于内容哈希），避免重复调用。
 * LLM 调用失败时自动回退到截断模式。
 *
 * @since 1.0.0
 */
@Slf4j
@Component
public class ContextCompressor {

    private static final int MAX_COMPRESSED_LENGTH = 4000;
    private static final Duration CACHE_TTL = Duration.ofHours(2);

    @Autowired(required = false)
    private LlmClientFactory llmClientFactory;

    @Autowired(required = false)
    private CacheService cacheService;

    @Value("${gewu.session.context-compress-mode:llm}")
    private String compressMode;

    @Value("${gewu.wenshi.llm.default-provider:qwen}")
    private String defaultLlmProvider;

    @Value("${gewu.wenshi.llm.default-model:qwen-plus}")
    private String defaultLlmModel;

    /**
     * 压缩对话历史。
     * <p>
     * 当 compressMode 为 "llm" 且 LLM 客户端可用时，调用 LLM 生成语义摘要；
     * 否则使用截断模式。LLM 调用失败时自动回退到截断。
     *
     * @param messages 待压缩的消息列表
     * @return 压缩后的文本
     */
    public String compress(List<MessageView> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }

        if ("llm".equals(compressMode) && llmClientFactory != null) {
            try {
                return compressViaLlm(messages);
            } catch (Exception e) {
                log.warn("ContextCompressor.compress: LLM summarization failed, falling back to truncation: {}", e.getMessage());
            }
        }

        return truncateCompress(messages);
    }

    /**
     * 使用 LLM 生成语义摘要。
     * <p>
     * 将消息拼接为对话文本，调用 LLM 生成不超过 800 token 的摘要。
     * 摘要结果基于内容哈希缓存，TTL 2 小时。
     */
    private String compressViaLlm(List<MessageView> messages) {
        String transcript = messages.stream()
                .map(m -> "[" + m.role() + "] " + m.content())
                .collect(Collectors.joining("\n"));

        // 缓存检查
        String cacheKey = "ctx-compress:" + transcript.hashCode();
        if (cacheService != null) {
            String cached = cacheService.get(cacheKey, String.class);
            if (cached != null) {
                log.debug("ContextCompressor.compressViaLlm: cache hit");
                return cached;
            }
        }

        // LLM 摘要
        String systemPrompt = "将以下对话历史压缩为不超过800 token的摘要，保留关键实体、数据、决策和未解决问题。直接输出摘要文本。";
        LlmRequest llmRequest = LlmRequest.builder()
                .model(defaultLlmModel)
                .messages(List.of(
                        Message.builder().role("system").content(systemPrompt).build(),
                        Message.builder().role("user").content(transcript).build()))
                .temperature(0.3)
                .maxTokens(1024)
                .stream(false)
                .build();

        LlmClient client = llmClientFactory.getClient(defaultLlmProvider);
        LlmResponse response = client.chat(llmRequest);
        String summary = response.getContent() != null ? response.getContent() : truncateCompress(messages);

        // 缓存结果
        if (cacheService != null && summary != null) {
            cacheService.set(cacheKey, summary, CACHE_TTL);
        }

        log.debug("ContextCompressor.compressViaLlm: summarized {} chars -> {} chars", transcript.length(), summary.length());
        return summary;
    }

    /**
     * 截断压缩（回退方案）：保留最近的消息直至达到长度上限。
     */
    private String truncateCompress(List<MessageView> messages) {
        StringBuilder sb = new StringBuilder();
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageView msg = messages.get(i);
            String entry = "[" + msg.role() + "] " + msg.content() + "\n";
            if (sb.length() + entry.length() > MAX_COMPRESSED_LENGTH) {
                break;
            }
            sb.insert(0, entry);
        }

        if (sb.length() > MAX_COMPRESSED_LENGTH) {
            return sb.substring(sb.length() - MAX_COMPRESSED_LENGTH);
        }
        return sb.toString();
    }

    public record MessageView(String role, String content) {
    }
}
