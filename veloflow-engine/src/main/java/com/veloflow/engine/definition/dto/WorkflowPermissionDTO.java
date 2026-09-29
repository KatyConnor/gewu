package com.veloflow.engine.definition.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 流程权限集（权限体系接线）：角色授权 + 节点类型办理矩阵（整体替换） */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowPermissionDTO {

    /** 角色授权：START 发起 / EXECUTE 执行 / REVIEW 审阅 / MANAGE 管理 */
    private List<PermissionGrant> permissions;

    /** 节点类型办理矩阵：未指派行的角色兜底 */
    private List<MatrixRule> matrix;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PermissionGrant {
        private String roleCode;
        private String permissionType;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MatrixRule {
        private String nodeType;
        private String requiredRole;
        /** APPROVE/EXECUTE 生效；VIEW 预留 */
        private String permissionLevel;
    }
}
