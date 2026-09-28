package com.veloflow.engine.definition.dto;

import lombok.Data;

/** 消息交付命令（53 号 §3.3 receive-message）：payload 为节点输出（JSON 文本） */
@Data
public class MessageDeliveryCommand {

    /** 可选：按消息键精确匹配（缺省命中最早等待中的消息节点） */
    private String messageKey;
    /** 消息载荷（JSON 文本） */
    private String payload;
}
