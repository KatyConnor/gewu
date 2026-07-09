package com.gewu.application.user.dto;

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
    private List<String> roleCodes;
}