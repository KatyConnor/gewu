package com.gewu.interfaceapi.controller;

import com.gewu.application.orchestration.OrchestrationCatalogService;
import com.gewu.application.orchestration.OrchestrationService;
import com.gewu.domain.orchestration.OrchestrationExecutionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * AE-P0-2：Webhook 匿名端点频控与开关语义测试（端点直调，不起 HTTP 栈）。
 * <p>红基线：频控实现前本用例编译失败（webhookRateLimited/webhookRateLimitPerMinute 不存在）。
 *
 * @author 牛牛
 * @version 1.0.0
 * @since 2026-11-12
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Webhook 端点频控（AE-P0-2）")
class OrchestrationWebhookRateLimitTest {

    @Mock
    private OrchestrationService orchestrationService;

    @Mock
    private OrchestrationCatalogService orchestrationCatalogService;

    private OrchestrationController controller;

    @BeforeEach
    void setUp() {
        controller = new OrchestrationController(orchestrationService, orchestrationCatalogService);
        ReflectionTestUtils.setField(controller, "webhookEnabled", true);
        ReflectionTestUtils.setField(controller, "webhookRateLimitPerMinute", 2);
    }

    private OrchestrationExecutionEntity pendingExecution() {
        OrchestrationExecutionEntity entity = new OrchestrationExecutionEntity();
        entity.setId("e-1");
        entity.setStatus("PENDING");
        return entity;
    }

    @Test
    @DisplayName("第 limit+1 次触发返回 429（RATE_LIMITED），前 limit 次 202 受理，不同 token 独立计数")
    void rateLimitRejectsAfterThreshold() {
        when(orchestrationService.triggerByWebhookAsync(anyString(), any()))
                .thenReturn(pendingExecution());

        assertThat(controller.triggerByWebhook("tok-1", "{}").getStatusCodeValue()).isEqualTo(202);
        assertThat(controller.triggerByWebhook("tok-1", "{}").getStatusCodeValue()).isEqualTo(202);
        assertThat(controller.triggerByWebhook("tok-1", "{}").getStatusCodeValue()).isEqualTo(429);
        // 不同 token 独立窗口
        assertThat(controller.triggerByWebhook("tok-2", "{}").getStatusCodeValue()).isEqualTo(202);
    }

    @Test
    @DisplayName("总开关关闭：一律 404（不暴露存在性，频控不参与）")
    void disabledSwitchReturns404() {
        ReflectionTestUtils.setField(controller, "webhookEnabled", false);
        assertThat(controller.triggerByWebhook("tok-1", "{}").getStatusCodeValue()).isEqualTo(404);
    }

    @Test
    @DisplayName("配置 ≤0 关闭频控：超限不再拒绝")
    void rateLimitZeroDisablesLimiting() {
        ReflectionTestUtils.setField(controller, "webhookRateLimitPerMinute", 0);
        lenient().when(orchestrationService.triggerByWebhookAsync(anyString(), any()))
                .thenReturn(pendingExecution());
        for (int i = 0; i < 5; i++) {
            assertThat(controller.triggerByWebhook("tok-1", "{}").getStatusCodeValue()).isEqualTo(202);
        }
    }
}
