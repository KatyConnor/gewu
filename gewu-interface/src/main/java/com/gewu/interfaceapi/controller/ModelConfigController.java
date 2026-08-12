package com.gewu.interfaceapi.controller;

import com.gewu.application.ai.ModelConfigService;
import com.gewu.application.ai.dto.CreateModelCommand;
import com.gewu.application.ai.dto.CreateProviderCommand;
import com.gewu.application.ai.dto.ModelConfigDTO;
import com.gewu.application.ai.dto.ProviderDTO;
import com.gewu.application.ai.dto.UpdateModelCommand;
import com.gewu.application.ai.dto.UpdateProviderCommand;
import com.gewu.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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

/**
 * 模型配置接口 — 管理供应商和模型。
 *
 * @since 1.0.0
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/models")
@RequiredArgsConstructor
@Tag(name = "模型配置", description = "供应商和模型管理接口")
public class ModelConfigController {

    private final ModelConfigService modelConfigService;

    // ==================== 供应商 ====================

    @GetMapping("/providers")
    @Operation(summary = "供应商列表", description = "获取所有供应商列表")
    public Result<List<ProviderDTO>> listProviders() {
        return Result.success(modelConfigService.listProviders());
    }

    @GetMapping("/providers/active")
    @Operation(summary = "已启用供应商", description = "获取已启用的供应商列表")
    public Result<List<ProviderDTO>> listActiveProviders() {
        return Result.success(modelConfigService.listActiveProviders());
    }

    @PostMapping("/providers")
    @Operation(summary = "创建供应商", description = "新增模型供应商")
    public Result<ProviderDTO> createProvider(@Valid @RequestBody CreateProviderCommand command) {
        return Result.success(modelConfigService.createProvider(command));
    }

    @PutMapping("/providers/{providerId}")
    @Operation(summary = "更新供应商", description = "更新供应商信息（编码不可修改）")
    public Result<ProviderDTO> updateProvider(@PathVariable String providerId,
                                              @Valid @RequestBody UpdateProviderCommand command) {
        return Result.success(modelConfigService.updateProvider(providerId, command));
    }

    @PostMapping("/providers/{providerId}/toggle")
    @Operation(summary = "切换供应商状态", description = "启用/停用供应商")
    public Result<Void> toggleProviderStatus(@PathVariable String providerId) {
        modelConfigService.toggleProviderStatus(providerId);
        return Result.success(null);
    }

    @DeleteMapping("/providers/{providerId}")
    @Operation(summary = "删除供应商", description = "删除供应商及其所有模型")
    public Result<Void> deleteProvider(@PathVariable String providerId) {
        modelConfigService.deleteProvider(providerId);
        return Result.success(null);
    }

    // ==================== 模型 ====================

    @GetMapping
    @Operation(summary = "模型列表", description = "获取所有模型列表")
    public Result<List<ModelConfigDTO>> listModels() {
        return Result.success(modelConfigService.listModels());
    }

    @GetMapping("/active")
    @Operation(summary = "已启用模型", description = "获取已启用的模型列表（用于会话页面模型选择）")
    public Result<List<ModelConfigDTO>> listActiveModels() {
        return Result.success(modelConfigService.listActiveModels());
    }

    @PostMapping
    @Operation(summary = "创建模型", description = "新增模型配置")
    public Result<ModelConfigDTO> createModel(@Valid @RequestBody CreateModelCommand command) {
        return Result.success(modelConfigService.createModel(command));
    }

    @PutMapping("/{modelId}")
    @Operation(summary = "更新模型", description = "更新模型配置（模型ID不可修改）")
    public Result<ModelConfigDTO> updateModel(@PathVariable String modelId,
                                               @Valid @RequestBody UpdateModelCommand command) {
        return Result.success(modelConfigService.updateModel(modelId, command));
    }

    @PostMapping("/{modelId}/toggle")
    @Operation(summary = "切换模型状态", description = "启用/停用模型")
    public Result<Void> toggleModelStatus(@PathVariable String modelId) {
        modelConfigService.toggleModelStatus(modelId);
        return Result.success(null);
    }

    @DeleteMapping("/{modelId}")
    @Operation(summary = "删除模型", description = "删除模型配置")
    public Result<Void> deleteModel(@PathVariable String modelId) {
        modelConfigService.deleteModel(modelId);
        return Result.success(null);
    }
}