package com.gewu.application.session.dto;

import lombok.Data;

/** 执行中转向（用户插话补充指令）请求体 */
@Data
public class SteerCommand {

    /** 补充指令内容（≤2000 字符，超限服务端截断） */
    private String content;

    /** 幂等键（用户消息落库去重用） */
    private String clientId;
}
