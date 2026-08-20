package com.gewu.interfaceapi.controller;

import com.gewu.application.sandbox.SandboxClient;
import com.gewu.common.dto.sandbox.CreateSandboxCommand;
import com.gewu.common.dto.sandbox.RenewExpireRequest;
import com.gewu.common.dto.sandbox.SandboxDTO;
import com.gewu.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 沙箱代理控制器 - 将 /api/v1/sandboxes 请求转发到独立的 gewu-sandbox 服务。
 * <p>
 * 前端统一直连 interface 服务（CORS 与 JWT 在此处理），开发环境不启动网关时，
 * 由本控制器经 SandboxClient 代理访问沙箱服务，保持前端调用路径与网关路由一致。
 *
 * @since 1.0.0
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sandboxes")
@RequiredArgsConstructor
@Tag(name = "沙箱管理", description = "沙箱实例的创建、启停、销毁与续期（代理转发至沙箱服务）")
public class SandboxProxyController {

    private final SandboxClient sandboxClient;

    @GetMapping
    @Operation(summary = "获取沙箱列表")
    public Result<List<SandboxDTO>> listSandboxes() {
        return Result.success(sandboxClient.listSandboxes());
    }

    @PostMapping
    @Operation(summary = "创建沙箱")
    public Result<SandboxDTO> createSandbox(@RequestBody CreateSandboxCommand command) {
        return Result.success(sandboxClient.createSandbox(command));
    }

    @GetMapping("/glm-5.3_common")
    @Operation(summary = "获取沙箱详情")
    public Result<SandboxDTO> getSandbox(@PathVariable String id) {
        return Result.success(sandboxClient.getSandbox(id));
    }

    @PostMapping("/glm-5.3_common/start")
    @Operation(summary = "启动沙箱")
    public Result<SandboxDTO> startSandbox(@PathVariable String id) {
        return Result.success(sandboxClient.startSandbox(id));
    }

    @PostMapping("/glm-5.3_common/stop")
    @Operation(summary = "停止沙箱")
    public Result<SandboxDTO> stopSandbox(@PathVariable String id) {
        return Result.success(sandboxClient.stopSandbox(id));
    }

    @DeleteMapping("/glm-5.3_common")
    @Operation(summary = "销毁沙箱")
    public Result<Void> deleteSandbox(@PathVariable String id) {
        sandboxClient.deleteSandbox(id);
        return Result.success();
    }

    /**
     * 续期沙箱。兼容三种请求体：
     * { expireSeconds }（相对秒数）、{ expireAt }（绝对毫秒时间戳）、{ ttlDays }（天数）。
     */
    @PutMapping("/glm-5.3_common/expire")
    @Operation(summary = "续期沙箱", description = "更新沙箱的过期时间")
    public Result<SandboxDTO> renewExpire(@PathVariable String id, @RequestBody Map<String, Object> body) {
        RenewExpireRequest request = new RenewExpireRequest();
        if (body.get("expireSeconds") instanceof Number seconds) {
            request.setExpireAt(System.currentTimeMillis() + seconds.longValue() * 1000);
        } else if (body.get("expireAt") instanceof Number expireAt) {
            request.setExpireAt(expireAt.longValue());
        } else if (body.get("ttlDays") instanceof Number ttlDays) {
            request.setTtlDays(ttlDays.intValue());
        }
        return Result.success(sandboxClient.renewExpire(id, request));
    }
}
