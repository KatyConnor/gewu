package com.gewu.admin.dto.user;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class UserDTO {

    private String userId;
    private String username;
    private String email;
    private String phone;
    private String displayName;
    private String avatarUrl;
    private Integer status;
    private Long lastLoginAt;
    private String orgId;
    private String orgName;
    private List<String> roleCodes;
    private List<String> permissions;
}