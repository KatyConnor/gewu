package com.gewu.domain.appsystem;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 应用系统子模块服务实体 - 系统下的服务/模块登记.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("app_service")
public class AppService extends BaseEntity {

    /** 所属应用系统ID */
    private String systemId;
    /** 服务编码（系统内唯一） */
    private String serviceCode;
    /** 服务名称 */
    private String serviceName;
    /** 服务类型：BACKEND/FRONTEND/GATEWAY/DATABASE/MIDDLEWARE/SCHEDULE/MQ */
    private String serviceType;
    /** 服务地址（如 http://host:port） */
    private String baseUrl;
    /** 健康检查路径（仅登记，不主动探测） */
    private String healthPath;
    /** 当前版本 */
    private String version;
    /** 服务负责人 */
    private String ownerName;
    /** 运行状态：1启用 2停用 */
    private Integer status;
    /** 服务描述 */
    private String description;
    /** 排序号 */
    private Integer sortOrder;
}
