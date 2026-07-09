package com.gewu.application.user.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateUserCommand {

    @Size(max = 64, message = "显示名称最长 64 个字符")
    private String displayName;

    @Size(max = 512, message = "头像 URL 最长 512 个字符")
    private String avatarUrl;

    @Size(max = 20, message = "手机号最长 20 个字符")
    private String phone;
}