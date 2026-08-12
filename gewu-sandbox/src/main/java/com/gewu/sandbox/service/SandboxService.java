package com.gewu.sandbox.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.common.context.UserContext;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.sandbox.Sandbox;
import com.gewu.domain.sandbox.SandboxAuditLog;
import com.gewu.infrastructure.mapper.SandboxAuditLogMapper;
import com.gewu.common.dto.sandbox.*;
import com.gewu.sandbox.mapper.SandboxMapper;
import com.gewu.sandbox.provider.SandboxProvider;
import com.gewu.sandbox.provider.SandboxProviderFactory;
import com.gewu.sandbox.template.SandboxTemplateMatcher;
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

        SandboxProvider provider = providerFactory.getDefaultProvider();
        Sandbox sandbox = provider.create(command);
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

        provider.start(sandbox);
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
        provider.start(sandbox);
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
        provider.stop(sandbox);
        sandbox.setStoppedAt(Instant.now().toEpochMilli());
        sandboxMapper.updateById(sandbox);

        logAudit(sandboxId, "STOP", "停止沙箱");
        return toDTO(sandbox);
    }

    @Transactional
    public void destroySandbox(String sandboxId) {
        Sandbox sandbox = getSandboxEntity(sandboxId);
        SandboxProvider provider = providerFactory.getDefaultProvider();
        provider.destroy(sandbox);
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
        return provider.exec(sandbox, command, timeoutSeconds);
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

        int timeout = request.getTimeout() != null ? request.getTimeout() : 30;

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
        try {
            SandboxAuditLog auditLog = new SandboxAuditLog();
            auditLog.setId(com.gewu.common.ulid.Ulid.next());
            auditLog.setSandboxId(sandboxId);
            auditLog.setAction(action);
            auditLog.setUserId(UserContext.currentUserId());
            auditLog.setDetails(details);
            auditLog.setTimestamp(Instant.now().toEpochMilli());
            auditLog.setCreatedAt(Instant.now().toEpochMilli());
            auditLogMapper.insert(auditLog);
        } catch (Exception e) {
            log.error("沙箱审计日志写入失败: sandboxId={}, action={}", sandboxId, action, e);
        }
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