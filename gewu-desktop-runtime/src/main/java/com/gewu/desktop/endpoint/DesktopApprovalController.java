package com.gewu.desktop.endpoint;

import com.gewu.desktop.security.ApprovalBroker;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 引擎 API Profile：写授权决策（D1-21）。
 * 渲染层弹窗（允许一次 / 本会话始终允许 / 拒绝）经此恢复引擎挂起的写入。
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/v1/approvals")
public class DesktopApprovalController {

    public record DecideRequest(boolean approve, boolean alwaysAllow) {
    }

    private final ApprovalBroker broker;

    public DesktopApprovalController(ApprovalBroker broker) {
        this.broker = broker;
    }

    @PostMapping("/{approvalId}/decide")
    public Map<String, Object> decide(@PathVariable String approvalId,
                                      @RequestBody DecideRequest req) {
        boolean found = broker.decide(approvalId, req.approve(),
                req.alwaysAllow() && req.approve());
        if (!found) {
            throw new IllegalArgumentException("授权请求不存在或已处理: " + approvalId);
        }
        return Map.of("ok", true);
    }
}
