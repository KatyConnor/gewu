package com.gewu.sandbox.controller;

import com.gewu.common.dto.PageQuery;
import com.gewu.common.result.PageResult;
import com.gewu.common.result.Result;
import com.gewu.sandbox.dto.CreateSandboxCommand;
import com.gewu.sandbox.dto.ExecCommandRequest;
import com.gewu.sandbox.dto.ExecCommandResponse;
import com.gewu.sandbox.dto.SandboxDTO;
import com.gewu.sandbox.service.SandboxService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/sandboxes")
@RequiredArgsConstructor
public class SandboxController {

    private final SandboxService sandboxService;

    @PostMapping
    public Result<SandboxDTO> createSandbox(@Valid @RequestBody CreateSandboxCommand command) {
        log.info("创建沙箱: image={}", command.getImage());
        return Result.success(sandboxService.createSandbox(command));
    }

    @PostMapping("/{id}/start")
    public Result<SandboxDTO> startSandbox(@PathVariable String id) {
        log.info("启动沙箱: {}", id);
        return Result.success(sandboxService.startSandbox(id));
    }

    @PostMapping("/{id}/stop")
    public Result<SandboxDTO> stopSandbox(@PathVariable String id) {
        log.info("停止沙箱: {}", id);
        return Result.success(sandboxService.stopSandbox(id));
    }

    @DeleteMapping("/{id}")
    public Result<Void> destroySandbox(@PathVariable String id) {
        log.info("销毁沙箱: {}", id);
        sandboxService.destroySandbox(id);
        return Result.success();
    }

    @GetMapping("/{id}")
    public Result<SandboxDTO> getSandbox(@PathVariable String id) {
        return Result.success(sandboxService.getSandbox(id));
    }

    @GetMapping
    public Result<PageResult<SandboxDTO>> listSandboxes(@Valid PageQuery query) {
        return Result.success(sandboxService.listSandboxes(query));
    }

    @PostMapping("/{id}/exec")
    public Result<ExecCommandResponse> execCommand(@PathVariable String id,
                                                   @Valid @RequestBody ExecCommandRequest request) {
        log.info("沙箱执行命令: sandboxId={}, command={}", id, request.getCommand());
        return Result.success(sandboxService.execCommand(id, request.getCommand(), request.getTimeout()));
    }
}
