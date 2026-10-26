package com.gewu.admin.dto.appsystem;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建子模块服务命令.
 */
@Data
public class CreateAppServiceCommand {

    @NotBlank(message = "服务编码不能为空")
    @Size(max = 64, message = "服务编码最长 64 字符")
    @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "服务编码仅允许字母/数字/下划线/中划线")
    private String serviceCode;

    @NotBlank(message = "服务名称不能为空")
    @Size(max = 128, message = "服务名称最长 128 字符")
    private String serviceName;

    @NotBlank(message = "服务类型不能为空")
    @Pattern(regexp = "^(BACKEND|FRONTEND|GATEWAY|DATABASE|MIDDLEWARE|SCHEDULE|MQ)$", message = "服务类型非法")
    private String serviceType;

    @Size(max = 256, message = "服务地址最长 256 字符")
    private String baseUrl;

    @Size(max = 128, message = "健康检查路径最长 128 字符")
    private String healthPath;

    @Size(max = 32, message = "版本最长 32 字符")
    private String version;

    @NotBlank(message = "服务负责人不能为空")
    @Size(max = 64, message = "服务负责人最长 64 字符")
    private String ownerName;

    private Integer status;

    @Size(max = 512, message = "服务描述最长 512 字符")
    private String description;

    private Integer sortOrder;
}
