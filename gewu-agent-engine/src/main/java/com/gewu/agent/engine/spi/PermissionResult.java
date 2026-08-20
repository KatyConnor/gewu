package com.gewu.agent.engine.spi;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具权限评估结果。
 *
 * @param effect        allow / deny / ask
 * @param reason        评估原因
 * @param requireApproval 是否需要人工审批（effect=ask 时为 true）
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PermissionResult {

    private String effect;
    private String reason;
    private boolean requireApproval;

    public static PermissionResult allow() {
        return new PermissionResult("allow", "默认允许", false);
    }

    public static PermissionResult deny(String reason) {
        return new PermissionResult("deny", reason, false);
    }

    public static PermissionResult ask(String reason) {
        return new PermissionResult("ask", reason, true);
    }
}
