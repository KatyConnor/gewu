package com.gewu.application.project.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProjectMemberDTO {

    private String userId;
    private String username;
    private String displayName;
    private String roleCode;
    private Long joinedAt;
}