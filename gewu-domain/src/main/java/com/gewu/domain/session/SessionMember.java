package com.gewu.domain.session;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("session_member")
public class SessionMember extends BaseEntity {

    private String sessionId;
    private String userId;
    private Integer role;
    private Long lastReadAt;
    private Integer isMuted;
    private Long joinedAt;
}