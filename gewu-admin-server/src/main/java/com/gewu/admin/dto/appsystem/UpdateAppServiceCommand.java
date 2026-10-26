package com.gewu.admin.dto.appsystem;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新子模块服务命令（编码不可变）.
 */
@Data
public class UpdateAppServiceCommand {

    @Size(max = 128, message = "服务名称最长 128 字符")
    private String serviceName;

    @Pattern(regexp = "^(BACKEND|FRONTEND|GATEWAY|DATABASE|MIDDLEWARE|SCHEDULE|MQ)$", message = "服务类型非法")
    private String serviceType;

    @Size(max = 256, message = "服务地址最长 256 字符")
    private String baseUrl;

    @Size(max = 128, message = "健康检查路径最长 128 字符")
    private String healthPath;

    @Size(max = 32, message = "版本最长 32 字符")
    private String version;

    @Size(max = 64, message = "服务负责人最长 64 字符")
    private String ownerName;

    private Integer status;

    @Size(max = 512, message = "服务描述最长 512 字符")
    private String description;

    private Integer sortOrder;
}
