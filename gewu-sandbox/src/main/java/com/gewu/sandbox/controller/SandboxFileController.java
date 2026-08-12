package com.gewu.sandbox.controller;

import com.gewu.common.dto.sandbox.SandboxDTO;
import com.gewu.common.result.Result;
import com.gewu.domain.sandbox.Sandbox;
import com.gewu.sandbox.mapper.SandboxMapper;
import com.gewu.sandbox.service.ContainerFileService;
import com.gewu.sandbox.service.SandboxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * 沙箱文件操作接口 - 通过 Docker cp API 实现容器内文件读写.
 * <p>供 SandboxClient (gewu-application) 通过 HTTP 调用，
 * 避免跨模块直接依赖 DockerClient。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sandboxes")
@RequiredArgsConstructor
@Tag(name = "沙箱文件操作", description = "容器内文件读写（Docker cp）")
public class SandboxFileController {

    private final SandboxMapper sandboxMapper;
    private final ContainerFileService containerFileService;
    private final SandboxService sandboxService;

    @PostMapping("/{id}/files/write")
    @Operation(summary = "写入文本文件", description = "通过 Docker cp 写入容器内文件")
    public Result<Void> writeFile(@PathVariable String id, @RequestBody WriteFileRequest request) {
        Sandbox sandbox = getSandboxEntity(id);
        containerFileService.writeTextFile(sandbox.getContainerId(), request.getPath(), request.getContent());
        return Result.success();
    }

    @GetMapping("/{id}/files/read")
    @Operation(summary = "读取文件内容", description = "通过 Docker cp 读取容器内文件")
    public ResponseEntity<String> readFile(@PathVariable String id, @RequestParam String path) {
        Sandbox sandbox = getSandboxEntity(id);
        String content = containerFileService.readTextFile(sandbox.getContainerId(), path);
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(content);
    }

    @PostMapping("/{id}/files/upload")
    @Operation(summary = "上传文件到容器")
    public Result<Void> uploadFile(
            @PathVariable String id,
            @RequestParam("path") String path,
            @RequestParam("file") MultipartFile file) throws IOException {
        Sandbox sandbox = getSandboxEntity(id);
        byte[] data = file.getBytes();
        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";
        containerFileService.uploadFile(sandbox.getContainerId(), path, data, fileName);
        return Result.success();
    }

    @GetMapping("/{id}/files/download")
    @Operation(summary = "从容器下载文件")
    public ResponseEntity<byte[]> downloadFile(@PathVariable String id, @RequestParam String path) {
        Sandbox sandbox = getSandboxEntity(id);
        byte[] data = containerFileService.downloadFile(sandbox.getContainerId(), path);
        String fileName = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .body(data);
    }

    private Sandbox getSandboxEntity(String id) {
        Sandbox sandbox = sandboxMapper.selectById(id);
        if (sandbox == null) {
            throw new com.gewu.common.result.BusinessException(
                    com.gewu.common.result.ResultCode.SANDBOX_NOT_FOUND);
        }
        return sandbox;
    }

    @lombok.Data
    public static class WriteFileRequest {
        private String path;
        private String content;
    }
}
