package com.gewu.sandbox.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.common.context.UserContext;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.sandbox.Sandbox;
import com.gewu.domain.sandbox.SandboxAuditLog;
import com.gewu.infrastructure.mapper.SandboxAuditLogMapper;
import com.gewu.common.dto.sandbox.*;
import com.gewu.sandbox.audit.SandboxAuditWriter;
import com.gewu.sandbox.constant.SandboxConstants;
import com.gewu.sandbox.mapper.SandboxMapper;
import com.gewu.sandbox.provider.SandboxProvider;
import com.gewu.sandbox.provider.SandboxProviderFactory;
import com.gewu.sandbox.template.SandboxTemplateMatcher;
import com.gewu.sandbox.validator.SandboxValidator;
import com.gewu.sandbox.template.SandboxTemplateMatcher.SandboxTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SandboxService {

    private final SandboxMapper sandboxMapper;
    private final SandboxAuditLogMapper auditLogMapper;
    private final SandboxProviderFactory providerFactory;
    private final SandboxTemplateMatcher templateMatcher;
    private final SandboxAuditWriter auditWriter;
    private final SandboxValidator sandboxValidator;

    @Value("${gewu.sandbox.lifecycle.manual-ttl-days:7}")
    private int manualTtlDays;

    @Value("${gewu.sandbox.lifecycle.agent-max-lifetime-seconds:300}")
    private int agentMaxLifetimeSeconds;

    @Value("${gewu.sandbox.lifecycle.project-ttl-days:0}")
    private int projectTtlDays;

    @Transactional
    public SandboxDTO createSandbox(CreateSandboxCommand command) {
        String source = command.getSource() != null ? command.getSource() : "manual";

        if (command.getTemplate() != null && !command.getTemplate().isBlank()) {
            SandboxTemplate template = templateMatcher.resolveFromTemplate(command.getTemplate());
            SandboxTemplateMatcher.CreateTemplateResult tplResult = templateMatcher.createFromTemplate(template);
            if (command.getImage() == null || command.getImage().isBlank()) {
                command.setImage(tplResult.image());
            }
            if (command.getCpuCores() == null) command.setCpuCores(tplResult.cpuCores());
            if (command.getMemoryMb() == null) command.setMemoryMb(tplResult.memoryMb());
            if (command.getDiskMb() == null) command.setDiskMb(tplResult.diskMb());
        }

        if (command.getImage() == null || command.getImage().isBlank()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "镜像或模板不能同时为空");
        }

        // 服务端准入校验：镜像白名单 + 资源上限（严于 DTO @Max，见 SandboxConstants）
        sandboxValidator.validateImage(command.getImage());
        sandboxValidator.validateResourceLimits(command.getCpuCores(), command.getMemoryMb(),
                command.getDiskMb(), command.getTimeout());

        SandboxProvider provider = providerFactory.getDefaultProvider();
        Sandbox sandbox;
        try {
            sandbox = provider.create(command);
        } catch (Exception e) {
            logAudit(command.getSandboxName(), "CREATE", "容器创建失败: " + e.getMessage(), "FAIL");
            throw e;
        }
        sandbox.setSandboxName(command.getSandboxName());
        sandbox.setCreatedBy(UserContext.currentUserId());
        sandbox.setSource(source);
        sandbox.setProjectId(command.getProjectId());
        sandbox.setAgentId(command.getAgentId());
        sandbox.setWorkspaceId(command.getWorkspaceId());
        sandbox.setAutoDestroy(command.getAutoDestroy() != null ? command.getAutoDestroy() : 0);

        if (command.getExpireAt() != null) {
            sandbox.setExpireAt(command.getExpireAt());
        } else if ("manual".equals(source) && manualTtlDays > 0) {
            sandbox.setExpireAt(Instant.now().plus(java.time.Duration.ofDays(manualTtlDays)).toEpochMilli());
        } else if ("project".equals(source) && projectTtlDays > 0) {
            sandbox.setExpireAt(Instant.now().plus(java.time.Duration.ofDays(projectTtlDays)).toEpochMilli());
        }

        sandboxMapper.insert(sandbox);
        sandbox.setLastUsedAt(Instant.now().toEpochMilli());

        try {
            provider.start(sandbox);
        } catch (Exception e) {
            logAudit(sandbox.getId(), "CREATE", "沙箱启动失败: " + e.getMessage(), "FAIL");
            throw e;
        }
        sandbox.setStartedAt(Instant.now().toEpochMilli());
        sandboxMapper.updateById(sandbox);

        logAudit(sandbox.getId(), "CREATE", "创建沙箱 source=" + source);

        return toDTO(sandbox);
    }

    @Transactional
    public SandboxDTO startSandbox(String sandboxId) {
        Sandbox sandbox = getSandboxEntity(sandboxId);
        if ("expired".equals(sandbox.getStatus())) {
            throw BusinessException.of(ResultCode.SANDBOX_EXPIRED);
        }
        SandboxProvider provider = providerFactory.getDefaultProvider();
        try {
            provider.start(sandbox);
        } catch (Exception e) {
            logAudit(sandboxId, "START", "启动失败: " + e.getMessage(), "FAIL");
            throw e;
        }
        sandbox.setStartedAt(Instant.now().toEpochMilli());
        sandbox.setLastUsedAt(Instant.now().toEpochMilli());
        sandboxMapper.updateById(sandbox);

        logAudit(sandboxId, "START", "启动沙箱");
        return toDTO(sandbox);
    }

    @Transactional
    public SandboxDTO stopSandbox(String sandboxId) {
        Sandbox sandbox = getSandboxEntity(sandboxId);
        SandboxProvider provider = providerFactory.getDefaultProvider();
        try {
            provider.stop(sandbox);
        } catch (Exception e) {
            logAudit(sandboxId, "STOP", "停止失败: " + e.getMessage(), "FAIL");
            throw e;
        }
        sandbox.setStoppedAt(Instant.now().toEpochMilli());
        sandboxMapper.updateById(sandbox);

        logAudit(sandboxId, "STOP", "停止沙箱");
        return toDTO(sandbox);
    }

    @Transactional
    public void destroySandbox(String sandboxId) {
        Sandbox sandbox = getSandboxEntity(sandboxId);
        SandboxProvider provider = providerFactory.getDefaultProvider();
        try {
            provider.destroy(sandbox);
        } catch (Exception e) {
            logAudit(sandboxId, "DESTROY", "销毁失败: " + e.getMessage(), "FAIL");
            throw e;
        }
        sandboxMapper.updateById(sandbox);

        logAudit(sandboxId, "DESTROY", "销毁沙箱");
    }

    public SandboxDTO getSandbox(String sandboxId) {
        return toDTO(getSandboxEntity(sandboxId));
    }

    public List<SandboxDTO> listSandboxes() {
        List<Sandbox> sandboxes = sandboxMapper.selectList(
                new LambdaQueryWrapper<Sandbox>().orderByDesc(Sandbox::getCreatedAt));
        return sandboxes.stream().map(this::toDTO).toList();
    }

    public ExecCommandResponse execCommand(String sandboxId, String command, Integer timeoutSeconds) {
        Sandbox sandbox = getSandboxEntity(sandboxId);
        if ("expired".equals(sandbox.getStatus())) {
            throw BusinessException.of(ResultCode.SANDBOX_EXPIRED);
        }

        sandbox.setLastUsedAt(Instant.now().toEpochMilli());
        sandboxMapper.updateById(sandbox);

        logAudit(sandboxId, "COMMAND", "执行命令: " + command);

        SandboxProvider provider = providerFactory.getDefaultProvider();
        try {
            return provider.exec(sandbox, command, timeoutSeconds);
        } catch (Exception e) {
            logAudit(sandboxId, "COMMAND", "命令执行异常: " + command, "FAIL");
            throw e;
        }
    }

    public List<SandboxAuditDTO> getSandboxLogs(String sandboxId) {
        LambdaQueryWrapper<SandboxAuditLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SandboxAuditLog::getSandboxId, sandboxId)
               .orderByDesc(SandboxAuditLog::getTimestamp);
        return auditLogMapper.selectList(wrapper).stream()
                .map(this::toAuditDTO)
                .toList();
    }

    @Transactional
    public ExecCommandResponse executeCode(ExecuteCodeRequest request) {
        SandboxTemplate template = templateMatcher.resolveTemplate(request.getLanguage());
        SandboxTemplateMatcher.CreateTemplateResult tplResult = templateMatcher.createFromTemplate(template);

        int timeout = Math.min(request.getTimeout() != null ? request.getTimeout() : 30,
                SandboxConstants.MAX_TIMEOUT_SECONDS);

        CreateSandboxCommand command = new CreateSandboxCommand();
        command.setSandboxName("agent-exec-" + System.currentTimeMillis());
        command.setImage(tplResult.image());
        command.setCpuCores(tplResult.cpuCores());
        command.setMemoryMb(tplResult.memoryMb());
        command.setDiskMb(tplResult.diskMb());
        command.setSource("agent");
        command.setAutoDestroy(1);
        command.setTimeout(timeout);
        command.setEnv(null);
        command.setCmd(null);

        SandboxProvider provider = providerFactory.getDefaultProvider();
        Sandbox sandbox = provider.create(command);
        sandbox.setCreatedBy(UserContext.currentUserId());
        sandbox.setSource("agent");
        sandbox.setAutoDestroy(1);
        sandboxMapper.insert(sandbox);

        provider.start(sandbox);
        sandbox.setStartedAt(Instant.now().toEpochMilli());
        sandboxMapper.updateById(sandbox);

        logAudit(sandbox.getId(), "EXECUTE_CODE", "Agent 执行 " + request.getLanguage() + " 代码");

        ExecCommandResponse response;
        try {
            response = provider.exec(sandbox, request.getCode(), timeout);
        } catch (Exception e) {
            logAudit(sandbox.getId(), "EXECUTE_CODE", "Agent 代码执行异常: " + e.getMessage(), "FAIL");
            throw e;
        } finally {
            try {
                provider.destroy(sandbox);
                sandboxMapper.updateById(sandbox);
            } catch (Exception e) {
                log.warn("Agent 沙箱自动销毁失败: sandboxId={}", sandbox.getId(), e);
            }
        }

        return response;
    }

    @Transactional
    public SandboxDTO createForProject(String projectId, CreateProjectSandboxCommand request) {
        SandboxTemplate template;
        if (request.getTemplate() != null && !request.getTemplate().isBlank()) {
            template = templateMatcher.resolveFromTemplate(request.getTemplate());
        } else {
            template = SandboxTemplate.SHELL;
        }
        SandboxTemplateMatcher.CreateTemplateResult tplResult = templateMatcher.createFromTemplate(template);

        CreateSandboxCommand command = new CreateSandboxCommand();
        command.setSandboxName(request.getSandboxName() != null ? request.getSandboxName() : "项目沙箱-" + projectId.substring(0, 8));
        command.setImage(tplResult.image());
        command.setCpuCores(tplResult.cpuCores());
        command.setMemoryMb(tplResult.memoryMb());
        command.setDiskMb(tplResult.diskMb());
        command.setSource("project");
        command.setProjectId(projectId);

        return createSandbox(command);
    }

    @Transactional
    public void destroyProjectSandboxes(String projectId) {
        List<Sandbox> projectSandboxes = sandboxMapper.selectList(
                new LambdaQueryWrapper<Sandbox>()
                        .eq(Sandbox::getProjectId, projectId)
                        .ne(Sandbox::getStatus, "destroyed"));
        SandboxProvider provider = providerFactory.getDefaultProvider();
        for (Sandbox sandbox : projectSandboxes) {
            try {
                provider.destroy(sandbox);
                sandboxMapper.updateById(sandbox);
                logAudit(sandbox.getId(), "DESTROY", "项目沙箱销毁 projectId=" + projectId);
            } catch (Exception e) {
                logAudit(sandbox.getId(), "DESTROY", "项目沙箱销毁失败: " + e.getMessage(), "FAIL");
                log.warn("项目沙箱销毁失败: sandboxId={}", sandbox.getId(), e);
            }
        }
    }

    @Transactional
    public SandboxDTO renewExpire(String sandboxId, RenewExpireRequest request) {
        Sandbox sandbox = getSandboxEntity(sandboxId);
        long newExpireAt;
        if (request.getExpireAt() != null) {
            newExpireAt = request.getExpireAt();
        } else if (request.getTtlDays() != null && request.getTtlDays() > 0) {
            newExpireAt = Instant.now().plus(java.time.Duration.ofDays(request.getTtlDays())).toEpochMilli();
        } else {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "expireAt 或 ttlDays 至少需要一个");
        }
        sandbox.setExpireAt(newExpireAt);
        if ("expired".equals(sandbox.getStatus())) {
            sandbox.setStatus(SandboxStatusCodeto("stopped"));
        }
        sandboxMapper.updateById(sandbox);

        logAudit(sandboxId, "RENEW", "续期至 " + newExpireAt);
        return toDTO(sandbox);
    }

    private String SandboxStatusCodeto(String code) {
        return code;
    }

    private void logAudit(String sandboxId, String action, String details) {
        logAudit(sandboxId, action, details, "SUCCESS");
    }

    /** 审计统一写入方（result: SUCCESS/FAIL；SM3 哈希链与脱敏在 SandboxAuditWriter 内维护） */
    private void logAudit(String sandboxId, String action, String details, String result) {
        // 沙箱内部线程可能无 UserContext（internal-key 调用），置 system 兜底
        String userId = UserContext.currentUserId();
        auditWriter.append(sandboxId, action, userId != null ? userId : "system", details, result);
    }

    /** 文件端点统一入口：文件操作计入活跃时间（防纯文件操作被误判空闲）+ FILE_ACCESS 审计 */
    public void recordFileActivity(String sandboxId, String accessType, String filePath) {
        Sandbox sandbox = getSandboxEntity(sandboxId);
        sandbox.setLastUsedAt(Instant.now().toEpochMilli());
        sandboxMapper.updateById(sandbox);
        logAudit(sandboxId, "FILE_ACCESS", accessType + ": " + filePath);
    }

    private Sandbox getSandboxEntity(String sandboxId) {
        Sandbox sandbox = sandboxMapper.selectById(sandboxId);
        if (sandbox == null) {
            throw BusinessException.of(ResultCode.SANDBOX_NOT_FOUND);
        }
        return sandbox;
    }

    private SandboxDTO toDTO(Sandbox sandbox) {
        String statusDesc = switch (sandbox.getStatus()) {
            case "creating" -> "创建中";
            case "running" -> "运行中";
            case "stopped" -> "已停止";
            case "destroyed" -> "已销毁";
            case "error" -> "异常";
            case "expired" -> "已过期";
            default -> sandbox.getStatus();
        };

        return SandboxDTO.builder()
                .sandboxId(sandbox.getId())
                .sandboxName(sandbox.getSandboxName())
                .status(sandbox.getStatus())
                .statusDesc(statusDesc)
                .image(sandbox.getImage())
                .cpuCores(sandbox.getCpuLimit())
                .memoryMb(sandbox.getMemoryLimitMb())
                .diskMb(sandbox.getDiskLimitMb())
                .networkEnabled(sandbox.getNetworkEnabled() != null && sandbox.getNetworkEnabled() == 1)
                .timeoutSeconds(sandbox.getTimeoutSeconds())
                .runtime(sandbox.getRuntime())
                .createdAt(sandbox.getCreatedAt())
                .startedAt(sandbox.getStartedAt())
                .stoppedAt(sandbox.getStoppedAt())
                .ip(sandbox.getIp())
                .ports(sandbox.getPorts())
                .createdBy(sandbox.getCreatedBy())
                .source(sandbox.getSource())
                .projectId(sandbox.getProjectId())
                .agentId(sandbox.getAgentId())
                .autoDestroy(sandbox.getAutoDestroy())
                .lastUsedAt(sandbox.getLastUsedAt())
                .expireAt(sandbox.getExpireAt())
                .workspaceId(sandbox.getWorkspaceId())
                .mountPath(sandbox.getWorkspaceId() != null ? "/workspace" : null)
                .build();
    }

    private SandboxAuditDTO toAuditDTO(SandboxAuditLog entity) {
        return SandboxAuditDTO.builder()
                .logId(entity.getId())
                .sandboxId(entity.getSandboxId())
                .action(entity.getAction())
                .status("success")
                .detail(entity.getDetails())
                .operatorId(entity.getUserId())
                .createdAt(entity.getTimestamp())
                .build();
    }
}