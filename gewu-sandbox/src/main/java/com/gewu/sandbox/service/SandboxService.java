package com.gewu.sandbox.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gewu.common.context.UserContext;
import com.gewu.common.dto.PageQuery;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.PageResult;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.sandbox.Sandbox;
import com.gewu.sandbox.dto.CreateSandboxCommand;
import com.gewu.sandbox.dto.ExecCommandResponse;
import com.gewu.sandbox.dto.SandboxDTO;
import com.gewu.sandbox.mapper.SandboxMapper;
import com.gewu.sandbox.provider.SandboxProvider;
import com.gewu.sandbox.provider.SandboxProviderFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SandboxService {

    private final SandboxMapper sandboxMapper;
    private final SandboxProviderFactory providerFactory;

    @Transactional
    public SandboxDTO createSandbox(CreateSandboxCommand command) {
        SandboxProvider provider = providerFactory.getDefaultProvider();
        Sandbox sandbox = provider.create(command);
        sandbox.setCreatedBy(UserContext.currentUserId());
        sandboxMapper.insert(sandbox);

        provider.start(sandbox);
        sandboxMapper.updateById(sandbox);

        return toDTO(sandbox);
    }

    @Transactional
    public SandboxDTO startSandbox(String sandboxId) {
        Sandbox sandbox = getSandboxEntity(sandboxId);
        SandboxProvider provider = providerFactory.getDefaultProvider();
        provider.start(sandbox);
        sandboxMapper.updateById(sandbox);
        return toDTO(sandbox);
    }

    @Transactional
    public SandboxDTO stopSandbox(String sandboxId) {
        Sandbox sandbox = getSandboxEntity(sandboxId);
        SandboxProvider provider = providerFactory.getDefaultProvider();
        provider.stop(sandbox);
        sandboxMapper.updateById(sandbox);
        return toDTO(sandbox);
    }

    @Transactional
    public void destroySandbox(String sandboxId) {
        Sandbox sandbox = getSandboxEntity(sandboxId);
        SandboxProvider provider = providerFactory.getDefaultProvider();
        provider.destroy(sandbox);
        sandboxMapper.updateById(sandbox);
    }

    public SandboxDTO getSandbox(String sandboxId) {
        return toDTO(getSandboxEntity(sandboxId));
    }

    public PageResult<SandboxDTO> listSandboxes(PageQuery query) {
        Page<Sandbox> page = new Page<>(query.getPage(), query.getSize());
        Page<Sandbox> result = sandboxMapper.selectPage(page,
                new LambdaQueryWrapper<Sandbox>().orderByDesc(Sandbox::getCreatedAt));

        List<SandboxDTO> dtos = result.getRecords().stream()
                .map(this::toDTO)
                .toList();
        return PageResult.of(dtos, result.getTotal(), query.getPage(), query.getSize());
    }

    public ExecCommandResponse execCommand(String sandboxId, String command, Integer timeoutSeconds) {
        Sandbox sandbox = getSandboxEntity(sandboxId);
        SandboxProvider provider = providerFactory.getDefaultProvider();
        return provider.exec(sandbox, command, timeoutSeconds);
    }

    private Sandbox getSandboxEntity(String sandboxId) {
        Sandbox sandbox = sandboxMapper.selectById(sandboxId);
        if (sandbox == null) {
            throw BusinessException.of(ResultCode.SANDBOX_NOT_FOUND);
        }
        return sandbox;
    }

    private SandboxDTO toDTO(Sandbox sandbox) {
        return SandboxDTO.builder()
                .id(sandbox.getId())
                .status(sandbox.getStatus())
                .image(sandbox.getImage())
                .cpuLimit(sandbox.getCpuLimit())
                .memoryLimit(sandbox.getMemoryLimitMb())
                .diskLimit(sandbox.getDiskLimitMb())
                .networkEnabled(sandbox.getNetworkEnabled() != null && sandbox.getNetworkEnabled() == 1)
                .timeoutSeconds(sandbox.getTimeoutSeconds())
                .runtime(sandbox.getRuntime())
                .createdAt(sandbox.getCreatedAt())
                .createdBy(sandbox.getCreatedBy())
                .build();
    }
}
