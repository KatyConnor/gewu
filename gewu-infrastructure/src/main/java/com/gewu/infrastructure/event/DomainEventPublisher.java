package com.gewu.infrastructure.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * 领域事件发布器 — 基于 RocketMQ 的异步事件发布.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DomainEventPublisher {

    private final RocketMQTemplate rocketMQTemplate;

    public void publish(String topic, Object payload) {
        Message<String> message = MessageBuilder
                .withPayload(toJson(payload))
                .build();
        rocketMQTemplate.syncSend(topic, message);
        log.debug("领域事件已发布: topic={}", topic);
    }

    public void publishAsync(String topic, Object payload) {
        Message<String> message = MessageBuilder
                .withPayload(toJson(payload))
                .build();
        rocketMQTemplate.asyncSend(topic, message, new org.apache.rocketmq.client.producer.SendCallback() {
            @Override
            public void onSuccess(org.apache.rocketmq.client.producer.SendResult result) {
                log.debug("异步领域事件发送成功: topic={}", topic);
            }

            @Override
            public void onException(Throwable e) {
                log.error("异步领域事件发送失败: topic={}", topic, e);
            }
        });
    }

    private String toJson(Object payload) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("领域事件序列化失败", e);
        }
    }
}