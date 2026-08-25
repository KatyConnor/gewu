package com.gewu.application.session;

import com.gewu.domain.session.Session;
import com.gewu.infrastructure.llm.LlmClient;
import com.gewu.infrastructure.llm.LlmClientFactory;
import com.gewu.infrastructure.llm.LlmRequest;
import com.gewu.infrastructure.llm.LlmResponse;
import com.gewu.infrastructure.llm.Message;
import com.gewu.infrastructure.mapper.SessionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 会话标题自动生成服务（T3.3）。
 * <p>首轮问答完成后异步生成不超过 12 字的会话标题，
 * 仅当当前标题为默认值（"新对话"）或空时写入；失败静默（保留默认标题）。
 * 使用独立守护线程池，不阻塞对话主链路。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
public class SessionTitleService {

    /** 默认标题（未生成前的占位值） */
    static final String DEFAULT_TITLE = "新对话";
    private static final int TITLE_MAX_LENGTH = 12;
    private static final String TITLE_PROMPT =
            "为以下对话生成一个不超过%d字的简洁标题，直接输出标题本身，不要任何解释或引号。";

    private final SessionMapper sessionMapper;
    private final LlmClientFactory llmClientFactory;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "session-title-gen");
        t.setDaemon(true);
        return t;
    });

    @Value("${gewu.wenshi.llm.default-provider:qwen}")
    private String defaultLlmProvider;

    @Value("${gewu.wenshi.llm.default-model:qwen-plus}")
    private String defaultLlmModel;

    public SessionTitleService(SessionMapper sessionMapper, LlmClientFactory llmClientFactory) {
        this.sessionMapper = sessionMapper;
        this.llmClientFactory = llmClientFactory;
    }

    /**
     * 异步生成标题（若需要）。首轮问答落库后调用。
     *
     * @param sessionId 会话 ID
     * @param userContent 首轮用户消息（截断使用）
     * @param assistantContent 首轮 AI 回复（截断使用）
     */
    public void generateIfAbsent(String sessionId, String userContent, String assistantContent) {
        if (sessionId == null || userContent == null || userContent.isBlank()) {
            return;
        }
        executor.submit(() -> doGenerate(sessionId, truncate(userContent, 100), truncate(assistantContent, 200)));
    }

    private void doGenerate(String sessionId, String userContent, String assistantContent) {
        try {
            Session session = sessionMapper.selectById(sessionId);
            if (session == null || hasCustomTitle(session)) {
                return;
            }
            LlmRequest request = LlmRequest.builder()
                    .model(defaultLlmModel)
                    .messages(List.of(
                            Message.builder().role("system")
                                    .content(String.format(TITLE_PROMPT, TITLE_MAX_LENGTH)).build(),
                            Message.builder().role("user")
                                    .content("用户：" + userContent + "\nAI：" + assistantContent).build()))
                    .temperature(0.3)
                    .maxTokens(32)
                    .stream(false)
                    .build();
            LlmClient client = llmClientFactory.getClient(defaultLlmProvider);
            LlmResponse response = client.chat(request);
            String title = sanitize(response != null ? response.getContent() : null);
            if (title != null) {
                // 二次校验避免并发覆盖用户手动重命名
                Session latest = sessionMapper.selectById(sessionId);
                if (latest != null && hasCustomTitle(latest)) {
                    return;
                }
                session.setTitle(title);
                sessionMapper.updateById(session);
                log.info("会话标题已生成: sessionId={}, title={}", sessionId, title);
            }
        } catch (Exception e) {
            log.warn("会话标题生成失败（保留默认标题）: sessionId={}, cause={}", sessionId, e.getMessage());
        }
    }

    private boolean hasCustomTitle(Session session) {
        String title = session.getTitle();
        return title != null && !title.isBlank() && !DEFAULT_TITLE.equals(title);
    }

    /** 标题清洗：去引号/换行/前后缀修饰，截断到上限 */
    private String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String title = raw.trim()
                .replaceAll("^[\"'“”]+|[\"'“”]+$", "")
                .replaceAll("[\\r\\n]+", " ")
                .replace("标题：", "").replace("标题:", "")
                .trim();
        if (title.isBlank()) {
            return null;
        }
        return title.length() > TITLE_MAX_LENGTH ? title.substring(0, TITLE_MAX_LENGTH) : title;
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** 优雅关闭（Spring 容器销毁时调用） */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
