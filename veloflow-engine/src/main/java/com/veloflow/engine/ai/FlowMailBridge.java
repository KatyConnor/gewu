package com.veloflow.engine.ai;

/**
 * 邮件桥 SPI（51 号 §2.6 email 节点，P6 二期）：SMTP 配置化由宿主管理
 * （spring.mail.*），引擎零宿主依赖。未接入/未配置时 email 节点以可读错误完成。
 *
 * @since 1.0.0
 */
public interface FlowMailBridge {

    /**
     * 发送纯文本邮件。
     *
     * @return 消息 ID（宿主返回，便于审计）
     */
    String send(String to, String subject, String body);
}
