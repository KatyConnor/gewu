package com.gewu.application.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RefreshTokenCommand {

    @NotBlank(message = "刷新令牌不能为空")
    private String refreshToken;
}