package com.gewu.common.enums;

/**
 * 需求管理权限常量.
 */
public final class RequirementPermission {

    private RequirementPermission() {}

    // ==================== 需求权限 ====================

    /** 查看需求 */
    public static final String REQUIREMENT_VIEW = "requirement:view";

    /** 创建需求 */
    public static final String REQUIREMENT_CREATE = "requirement:create";

    /** 编辑需求 */
    public static final String REQUIREMENT_EDIT = "requirement:edit";

    /** 删除需求 */
    public static final String REQUIREMENT_DELETE = "requirement:delete";

    /** 变更需求状态 */
    public static final String REQUIREMENT_STATUS_CHANGE = "requirement:status:change";

    /** 提交需求评审 */
    public static final String REQUIREMENT_REVIEW_SUBMIT = "requirement:review:submit";

    // ==================== 评审权限 ====================

    /** 执行需求评审 */
    public static final String REVIEW_EXECUTE = "review:execute";

    /** 执行设计评审 */
    public static final String REVIEW_DESIGN_EXECUTE = "review:design:execute";

    /** 执行测试案例评审 */
    public static final String REVIEW_TEST_EXECUTE = "review:test:execute";

    // ==================== 任务权限 ====================

    /** 创建任务 */
    public static final String TASK_CREATE = "task:create";

    /** 编辑任务 */
    public static final String TASK_EDIT = "task:edit";

    /** 删除任务 */
    public static final String TASK_DELETE = "task:delete";

    /** 分配任务 */
    public static final String TASK_ASSIGN = "task:assign";

    // ==================== 角色定义 ====================

    /** 产品经理角色 */
    public static final String ROLE_PRODUCT_MANAGER = "PRODUCT_MANAGER";

    /** 项目经理角色 */
    public static final String ROLE_PROJECT_MANAGER = "PROJECT_MANAGER";

    /** 技术负责人角色 */
    public static final String ROLE_TECH_LEAD = "TECH_LEAD";

    /** 测试负责人角色 */
    public static final String ROLE_TEST_LEAD = "TEST_LEAD";

    /** 开发工程师角色 */
    public static final String ROLE_DEVELOPER = "DEVELOPER";

    /** 测试工程师角色 */
    public static final String ROLE_TESTER = "TESTER";
}
