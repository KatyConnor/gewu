package com.gewu.admin.dto.appsystem;

import lombok.Builder;
import lombok.Data;

/**
 * 应用系统子模块服务 DTO.
 */
@Data
@Builder
public class AppServiceDTO {

    private String serviceId;
    private String systemId;
    private String serviceCode;
    private String serviceName;
    /** 服务类型：BACKEND/FRONTEND/GATEWAY/DATABASE/MIDDLEWARE/SCHEDULE/MQ */
    private String serviceType;
    private String baseUrl;
    private String healthPath;
    private String version;
    private String ownerName;
    /** 运行状态：1启用 2停用 */
    private Integer status;
    private String description;
    private Integer sortOrder;
    private Long createdAt;
    private Long updatedAt;
}
