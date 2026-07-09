package com.gewu.domain.session;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseSimpleEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("session_input")
public class SessionInput extends BaseSimpleEntity {

    private String sessionId;
    private String prompt;
    private String delivery;
    private Integer admittedSeq;
    private Integer promotedSeq;
}