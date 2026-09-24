package com.gewu.application.ai;

import com.gewu.domain.ai.ModelConfig;
import com.gewu.domain.session.Session;
import com.gewu.infrastructure.mapper.ModelConfigMapper;
import com.gewu.infrastructure.mapper.SessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CostAccountingService} 成本核算测试（T4.1）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("会话成本核算")
class CostAccountingServiceTest {

    @Mock
    private SessionMapper sessionMapper;

    @Mock
    private ModelConfigMapper modelConfigMapper;

    @Mock
    private com.gewu.infrastructure.mapper.UsageLedgerMapper usageLedgerMapper;

    private CostAccountingService service;

    @BeforeEach
    void setUp() {
        service = new CostAccountingService(sessionMapper, modelConfigMapper, usageLedgerMapper);
    }

    private void priceFor(String modelId, double in, double out) {
        ModelConfig config = new ModelConfig();
        config.setModelId(modelId);
        config.setPricePer1kInput(BigDecimal.valueOf(in));
        config.setPricePer1kOutput(BigDecimal.valueOf(out));
        lenient().when(modelConfigMapper.selectList(any())).thenReturn(List.of(config));
    }

    @Test
    @DisplayName("真实 usage：按单价计算成本并原子累计")
    void recordUsageWithPrice() {
        priceFor("qwen-plus", 0.008, 0.024);

        service.recordUsage("sess-1", "qwen-plus", 1000, 2000, 300);

        // cost = 1000/1000*0.008 + 2000/1000*0.024 = 0.056
        ArgumentCaptor<BigDecimal> costCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(sessionMapper).appendUsage(eq("sess-1"), eq(1000), eq(2000), eq(300), costCaptor.capture());
        assertEquals(0, BigDecimal.valueOf(0.056).compareTo(costCaptor.getValue()));
    }

    @Test
    @DisplayName("无单价配置：token 累计但成本为 0")
    void recordUsageWithoutPrice() {
        when(modelConfigMapper.selectList(any())).thenReturn(List.of());

        service.recordUsage("sess-1", "unknown-model", 500, 500, 0);

        verify(sessionMapper).appendUsage(eq("sess-1"), eq(500), eq(500), eq(0),
                org.mockito.ArgumentMatchers.argThat(c -> c != null && c.compareTo(BigDecimal.ZERO) == 0));
    }

    @Test
    @DisplayName("流式估算：输入按消息字符/4，输出按回复字符/4")
    void estimatedUsage() {
        priceFor("qwen-plus", 0.008, 0.024);

        service.recordEstimatedUsage("sess-1", "qwen-plus", "你好世界！".repeat(100), "答".repeat(400));

        // input = 500 字/4 = 125, output = 400 字/4 = 100
        verify(sessionMapper).appendUsage(eq("sess-1"), eq(125), eq(100), eq(0), any(BigDecimal.class));
    }

    @Test
    @DisplayName("空会话 ID 跳过；核算失败不抛异常")
    void nullSessionSkippedAndFailureSwallowed() {
        service.recordUsage(null, "m", 100, 100, 0);
        verify(sessionMapper, never()).appendUsage(anyString(), anyInt(), anyInt(), anyInt(), any());

        when(sessionMapper.appendUsage(anyString(), anyInt(), anyInt(), anyInt(), any()))
                .thenThrow(new RuntimeException("db down"));
        service.recordUsage("sess-1", "m", 100, 100, 0); // 不抛异常
    }
}
