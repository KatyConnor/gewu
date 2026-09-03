package com.gewu.infrastructure.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link DomainEventPublisher} Spring ApplicationEvent 本地实现测试（S6 移除 RocketMQ 后）。
 */
@DisplayName("领域事件发布器")
class DomainEventPublisherTest {

    @Test
    @DisplayName("publish 发布 DomainEvent 信封：topic 与 JSON 载荷完整")
    void publishWrapsEnvelope() {
        org.springframework.context.ApplicationEventPublisher publisher =
                mock(org.springframework.context.ApplicationEventPublisher.class);
        DomainEventPublisher publisherService = new DomainEventPublisher();
        publisherService.setApplicationEventPublisher(publisher);

        publisherService.publish("session.created", java.util.Map.of("sessionId", "sess-1"));

        ArgumentCaptor<DomainEventPublisher.DomainEvent> captor =
                ArgumentCaptor.forClass(DomainEventPublisher.DomainEvent.class);
        verify(publisher).publishEvent(captor.capture());
        assertEquals("session.created", captor.getValue().getTopic());
        assertTrue(captor.getValue().getPayload().contains("sess-1"));
    }

    @Test
    @DisplayName("publishAsync 保持 API 兼容：进程内立即派发")
    void publishAsyncCompatible() {
        org.springframework.context.ApplicationEventPublisher publisher =
                mock(org.springframework.context.ApplicationEventPublisher.class);
        DomainEventPublisher publisherService = new DomainEventPublisher();
        publisherService.setApplicationEventPublisher(publisher);

        publisherService.publishAsync("agent.execution.completed", "payload");

        verify(publisher).publishEvent(any(DomainEventPublisher.DomainEvent.class));
    }

    @Test
    @DisplayName("未初始化发布器时静默跳过")
    void nullPublisherSkipped() {
        DomainEventPublisher publisherService = new DomainEventPublisher();
        // 不抛异常
        publisherService.publish("t", new Object());
        publisherService.publishAsync("t", new Object());
    }
}
