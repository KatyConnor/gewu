package com.gewu.application.session;

import com.gewu.domain.session.Session;
import com.gewu.infrastructure.llm.LlmClient;
import com.gewu.infrastructure.llm.LlmClientFactory;
import com.gewu.infrastructure.llm.LlmResponse;
import com.gewu.infrastructure.mapper.SessionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SessionTitleService} 标题自动生成测试（T3.3）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("会话标题自动生成")
class SessionTitleServiceTest {

    @Mock
    private SessionMapper sessionMapper;

    @Mock
    private LlmClientFactory llmClientFactory;

    @Mock
    private LlmClient llmClient;

    private SessionTitleService titleService;

    @BeforeEach
    void setUp() throws Exception {
        titleService = new SessionTitleService(sessionMapper, llmClientFactory);
        // @Value 字段无 Spring 环境时为 null，反射注入默认值
        setField(titleService, "defaultLlmProvider", "qwen");
        setField(titleService, "defaultLlmModel", "qwen-plus");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @AfterEach
    void tearDown() {
        titleService.shutdown();
    }

    private Session defaultTitleSession() {
        Session session = new Session();
        session.setId("sess-1");
        session.setTitle(SessionTitleService.DEFAULT_TITLE);
        return session;
    }

    @Test
    @DisplayName("默认标题的会话：首轮问答后异步生成并更新标题")
    void generatesTitleForDefaultSession() {
        Session session = defaultTitleSession();
        when(sessionMapper.selectById("sess-1")).thenReturn(session);
        when(llmClientFactory.getClient(anyString())).thenReturn(llmClient);
        when(llmClient.chat(any())).thenReturn(
                LlmResponse.builder().content("\"北京天气查询\"").build());

        titleService.generateIfAbsent("sess-1", "北京今天天气怎么样", "晴，25度");

        // 异步执行：等待标题写入（含引号清洗）
        verify(sessionMapper, timeout(2000)).updateById(argThat(s ->
                "北京天气查询".equals(s.getTitle())));
    }

    @Test
    @DisplayName("已有自定义标题：不生成")
    void skipsCustomTitle() {
        Session session = defaultTitleSession();
        session.setTitle("用户手动命名");
        when(sessionMapper.selectById("sess-1")).thenReturn(session);

        titleService.generateIfAbsent("sess-1", "你好", "你好！");
        titleService.shutdown();

        verify(sessionMapper, never()).updateById(any(Session.class));
    }

    @Test
    @DisplayName("LLM 失败：静默保留默认标题")
    void llmFailureSilentlyIgnored() {
        Session session = defaultTitleSession();
        when(sessionMapper.selectById("sess-1")).thenReturn(session);
        when(llmClientFactory.getClient(anyString())).thenReturn(llmClient);
        when(llmClient.chat(any())).thenThrow(new RuntimeException("provider down"));

        titleService.generateIfAbsent("sess-1", "写一首诗", "好的");
        titleService.shutdown();

        verify(sessionMapper, never()).updateById(any(Session.class));
    }

    @Test
    @DisplayName("标题清洗：去引号/换行/前缀并截断到 12 字")
    void sanitizeTitle() {
        Session session = defaultTitleSession();
        when(sessionMapper.selectById("sess-1")).thenReturn(session);
        when(llmClientFactory.getClient(anyString())).thenReturn(llmClient);
        when(llmClient.chat(any())).thenReturn(
                LlmResponse.builder().content("标题：这是一个特别特别特别长的标题\n").build());

        titleService.generateIfAbsent("sess-1", "写一段介绍", "好的");
        titleService.shutdown();

        verify(sessionMapper).updateById(argThat(s ->
                s.getTitle().length() <= 12 && !s.getTitle().contains("标题：")
                        && !s.getTitle().contains("\n")));
    }

    @Test
    @DisplayName("空输入直接跳过（不触发 LLM）")
    void blankInputSkipped() {
        titleService.generateIfAbsent("sess-1", "  ", null);
        titleService.shutdown();

        verify(sessionMapper, never()).selectById(anyString());
        assertTrue(true);
    }
}
