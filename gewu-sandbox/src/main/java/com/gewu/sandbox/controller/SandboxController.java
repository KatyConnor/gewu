package com.gewu.sandbox.controller;

import com.gewu.common.result.Result;
import com.gewu.common.dto.sandbox.*;
import com.gewu.sandbox.service.SandboxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/sandboxes")
@RequiredArgsConstructor
@Tag(name = "沙箱管理", description = "沙箱实例的创建、启停、执行和查询")
public class SandboxController {

    private final SandboxService sandboxService;

    @PostMapping
    @Operation(summary = "创建沙箱", description = "手动创建并启动一个新的沙箱实例")
    public Result<SandboxDTO> createSandbox(@Valid @RequestBody CreateSandboxCommand command) {
        log.info("创建沙箱: image={}, template={}, source={}",
                command.getImage(), command.getTemplate(), command.getSource());
        return Result.success(sandboxService.createSandbox(command));
    }

    @PostMapping("/{id}/start")
    @Operation(summary = "启动沙箱")
    public Result<SandboxDTO> startSandbox(@PathVariable String id) {
        log.info("启动沙箱: {}", id);
        return Result.success(sandboxService.startSandbox(id));
    }

    @PostMapping("/{id}/stop")
    @Operation(summary = "停止沙箱")
    public Result<SandboxDTO> stopSandbox(@PathVariable String id) {
        log.info("停止沙箱: {}", id);
        return Result.success(sandboxService.stopSandbox(id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "销毁沙箱")
    public Result<Void> destroySandbox(@PathVariable String id) {
        log.info("销毁沙箱: {}", id);
        sandboxService.destroySandbox(id);
        return Result.success();
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取沙箱详情")
    public Result<SandboxDTO> getSandbox(@PathVariable String id) {
        return Result.success(sandboxService.getSandbox(id));
    }

    @GetMapping
    @Operation(summary = "获取沙箱列表", description = "获取所有沙箱实例列表")
    public Result<List<SandboxDTO>> listSandboxes() {
        return Result.success(sandboxService.listSandboxes());
    }

    @PostMapping("/{id}/exec")
    @Operation(summary = "执行命令", description = "在沙箱中执行命令")
    @PreAuthorize("hasAuthority('sandbox:manage')")
    public Result<ExecCommandResponse> execCommand(@PathVariable String id,
                                                   @Valid @RequestBody ExecCommandRequest request) {
        log.info("沙箱执行命令: sandboxId={}, command={}", id, request.getCommand());
        return Result.success(sandboxService.execCommand(id, request.getCommand(), request.getTimeout()));
    }

    @GetMapping("/{id}/logs")
    @Operation(summary = "获取操作日志", description = "获取指定沙箱的操作日志")
    public Result<List<SandboxAuditDTO>> getSandboxLogs(@PathVariable String id) {
        return Result.success(sandboxService.getSandboxLogs(id));
    }

    @PostMapping("/execute")
    @Operation(summary = "Agent 自动执行代码", description = "Agent 自动创建临时沙箱执行代码，执行完毕后自动销毁")
    @PreAuthorize("hasAuthority('sandbox:manage')")
    public Result<ExecCommandResponse> executeCode(@Valid @RequestBody ExecuteCodeRequest request) {
        log.info("Agent 执行代码: language={}, codeLength={}", request.getLanguage(), request.getCode().length());
        return Result.success(sandboxService.executeCode(request));
    }

    @PostMapping("/project/{projectId}")
    @Operation(summary = "创建项目绑定沙箱", description = "为项目创建绑定的开发环境沙箱")
    public Result<SandboxDTO> createProjectSandbox(@PathVariable String projectId,
                                                    @RequestBody CreateProjectSandboxCommand request) {
        log.info("创建项目沙箱: projectId={}, template={}", projectId, request.getTemplate());
        return Result.success(sandboxService.createForProject(projectId, request));
    }

    @DeleteMapping("/project/{projectId}")
    @Operation(summary = "销毁项目绑定沙箱", description = "销毁项目下所有绑定的沙箱")
    public Result<Void> destroyProjectSandboxes(@PathVariable String projectId) {
        log.info("销毁项目沙箱: projectId={}", projectId);
        sandboxService.destroyProjectSandboxes(projectId);
        return Result.success();
    }

    @PutMapping("/{id}/expire")
    @Operation(summary = "续期沙箱", description = "更新沙箱的过期时间")
    public Result<SandboxDTO> renewExpire(@PathVariable String id,
                                          @Valid @RequestBody RenewExpireRequest request) {
        log.info("续期沙箱: sandboxId={}, expireAt={}", id, request.getExpireAt());
        return Result.success(sandboxService.renewExpire(id, request));
    }
}