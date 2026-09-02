package com.gewu.application.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.hitl.HumanDecision;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * SSE 多副本分布式配置（T5.1）。
 * <p>启用条件：{@code gewu.sse.distributed=true}（默认 false，单副本零依赖）。
 * 订阅 Redis 频道 {@code gewu:sse:broadcast}，按 scope 路由本地投递：
 * <ul>
 *   <li>session - 会话连接本地投递（协作消息/HITL 审批通知）</li>
 *   <li>user - 用户连接本地投递</li>
 *   <li>hitl - 审批决策回传：实例 A 挂起等待审批、实例 B 上的管理员提交决策时，
 *       B 本地无 Sink 则经频道回传给 A 完成恢复</li>
 * </ul>
 * 自发消息按 origin 实例 ID 跳过（发布前已本地直发，防双投）。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "gewu.sse.distributed", havingValue = "true")
public class SseDistributedConfig {

    @Bean
    public RedisMessageListenerContainer sseListenerContainer(
            RedisConnectionFactory connectionFactory,
            SseEventManager eventManager,
            SseBroadcastService broadcastService,
            ObjectProvider<com.gewu.application.agent.adapter.DbHitlGatewayAdapter> hitlProvider,
            ObjectMapper objectMapper) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            String raw = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
            SseBroadcastMessage msg = broadcastService.parse(raw);
            if (msg == null) return;
            // 防自环：发布实例已本地直发
            if (msg.origin().equals(broadcastService.getInstanceId())) return;

            try {
                switch (msg.scope()) {
                    case SseBroadcastMessage.SCOPE_SESSION -> eventManager.sendLocalSession(
                            msg.target(), msg.eventName(), objectMapper.readValue(msg.payload(), Object.class));
                    case SseBroadcastMessage.SCOPE_USER -> eventManager.sendLocalUser(
                            msg.target(), msg.eventName(), objectMapper.readValue(msg.payload(), Object.class));
                    case SseBroadcastMessage.SCOPE_HITL -> {
                        com.gewu.application.agent.adapter.DbHitlGatewayAdapter hitl =
                                hitlProvider.getIfAvailable();
                        if (hitl != null) {
                            hitl.completeLocal(msg.target(),
                                    objectMapper.readValue(msg.payload(), HumanDecision.class));
                        }
                    }
                    default -> log.debug("未知 SSE 广播 scope: {}", msg.scope());
                }
            } catch (Exception e) {
                log.warn("SSE 广播路由失败: scope={}, target={}, cause={}",
                        msg.scope(), msg.target(), e.getMessage());
            }
        }, new ChannelTopic(SseBroadcastService.CHANNEL));

        log.info("SSE 分布式订阅已启用: channel={}, instanceId={}",
                SseBroadcastService.CHANNEL, broadcastService.getInstanceId());
        return container;
    }
}
