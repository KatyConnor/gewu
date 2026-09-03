package com.gewu.infrastructure.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.stereotype.Component;


/**
 * 领域事件发布器 - 基于 Spring ApplicationEvent 的进程内事件发布（S6 移除 RocketMQ 后本地实现）。
 * <p>事件语义保留（监听器可按 topic 过滤 {@link DomainEvent}），跨实例事件待真实需求出现
 * 再引入 MQ（docs/design/44 门 2 终审结论）。原 RocketMQTemplate 实现已移除。
 * <p>使用方式：监听器注册 {@code @EventListener} 方法接收 {@link DomainEvent}，
 * 按 {@code event.getTopic()} 区分事件类型。
 *
 * @since 1.0.0
 */
@Slf4j
@Component
public class DomainEventPublisher implements ApplicationEventPublisherAware {

    private ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.eventPublisher = applicationEventPublisher;
    }

    /** 发布领域事件（进程内同步派发，监听器异常不会阻断调用方——由 Spring 事件机制隔离） */
    public void publish(String topic, Object payload) {
        if (eventPublisher == null) {
            log.debug("事件发布器未初始化，跳过领域事件发布: topic={}", topic);
            return;
        }
        String json = toJson(payload);
        eventPublisher.publishEvent(new DomainEvent(this, topic, json));
        log.debug("领域事件已发布: topic={}, payloadLen={}", topic, json.length());
    }

    /**
     * 异步语义发布（API 兼容保留）：进程内事件本无网络异步，实现为立即发布；
     * 耗时监听器请自行使用 @Async/线程池。
     */
    public void publishAsync(String topic, Object payload) {
        publish(topic, payload);
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("领域事件序列化失败", e);
        }
    }

    /** 领域事件信封：topic 区分类型，payload 为 JSON 字符串（时间戳由基类 getTimestamp() 提供） */
    public static class DomainEvent extends org.springframework.context.ApplicationEvent {
        private final String topic;
        private final String payload;

        public DomainEvent(Object source, String topic, String payload) {
            super(source);
            this.topic = topic;
            this.payload = payload;
        }

        public String getTopic() {
            return topic;
        }

        public String getPayload() {
            return payload;
        }
    }
}
