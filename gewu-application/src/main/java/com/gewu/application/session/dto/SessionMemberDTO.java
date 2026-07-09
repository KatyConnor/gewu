package com.gewu.application.session.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SessionMemberDTO {

    private String userId;
    private Integer role;
    private Long joinedAt;
}
