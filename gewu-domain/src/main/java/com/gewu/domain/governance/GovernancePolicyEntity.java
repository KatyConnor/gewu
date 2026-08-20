package com.gewu.domain.governance;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 治理策略 - PolicyService 的持久化实体，按场景版本化管理，支持激活/回滚。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("governance_policy")
public class GovernancePolicyEntity extends BaseEntity {

    /** 策略场景: model_routing/tool_permission/hitl_threshold 等 */
    private String scenario;
    /** 策略名称 */
    private String policyName;
    /** 策略规则 JSON（allow/deny + 场景自定义参数） */
    private String ruleJson;
    /** 版本号（同场景递增） */
    private Integer version;
    /** 是否当前生效 */
    private Integer active;
    /** 策略说明 */
    private String description;
}
