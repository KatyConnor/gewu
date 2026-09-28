package com.gewu.application.config;

import com.veloflow.engine.ai.FlowMailBridge;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Veloflow 邮件桥（51 号 §2.6，P6 二期）：email 节点的宿主实现——
 * spring.mail.* 配置化 SMTP（starter-mail 装配），veloflow.mail.enabled=true 时启用；
 * 发件人取 veloflow.mail.from（缺省用 spring.mail.username）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "veloflow.mail.enabled", havingValue = "true")
public class VeloflowMailBridge implements FlowMailBridge {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final String from;

    public VeloflowMailBridge(ObjectProvider<JavaMailSender> mailSenderProvider,
                              org.springframework.core.env.Environment environment) {
        this.mailSenderProvider = mailSenderProvider;
        this.from = environment.getProperty("veloflow.mail.from",
                environment.getProperty("spring.mail.username", "noreply@gewu.local"));
    }

    @Override
    public String send(String to, String subject, String body) {
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            throw new IllegalStateException("JavaMailSender 不可用（spring.mail.host 未配置）");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to.split("[,;\\s]+"));
        message.setSubject(subject);
        message.setText(body);
        sender.send(message);
        log.info("工作流邮件发送: to={}, subject={}", to, subject);
        return "sent-" + System.currentTimeMillis();
    }
}
