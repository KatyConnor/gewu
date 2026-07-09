package com.gewu.domain.session;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

@Data
@TableName("session_context_epoch")
public class SessionContextEpoch implements Serializable {

    private String sessionId;
    private String baseline;
    private String snapshot;
    private Integer baselineSeq;
}