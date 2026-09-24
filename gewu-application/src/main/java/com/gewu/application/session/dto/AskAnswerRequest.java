package com.gewu.application.session.dto;

import lombok.Data;

/** ask_user 问答的用户回答请求体（忽略=空，网关以跳过应答回灌） */
@Data
public class AskAnswerRequest {

    /** 用户选择/输入的回答；空=跳过 */
    private String answer;
}
