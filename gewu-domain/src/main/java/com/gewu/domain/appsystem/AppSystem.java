package com.gewu.domain.appsystem;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 应用系统实体 - 企业应用台账（CMDB-lite）.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("app_system")
public class AppSystem extends BaseEntity {

    /** 系统编码（全局唯一） */
    private String systemCode;
    /** 系统名称（全称） */
    private String systemName;
    /** 系统简称 */
    private String shortName;
    /** 系统分类：BUSINESS/PLATFORM/MIDDLEWARE/DATA/TOOL */
    private String systemType;
    /** 所属机构ID（关联 organization） */
    private String orgId;
    /** 技术负责人 */
    private String ownerName;
    /** 负责人联系方式 */
    private String ownerContact;
    /** 代码仓库地址 */
    private String repoUrl;
    /** 重要等级：1核心 2重要 3一般 */
    private Integer criticality;
    /** 运行状态：1启用 2停用 */
    private Integer status;
    /** 系统描述 */
    private String description;
    /** 排序号 */
    private Integer sortOrder;
}
