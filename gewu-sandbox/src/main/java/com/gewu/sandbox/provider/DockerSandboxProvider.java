package com.gewu.sandbox.provider;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.gewu.common.enums.SandboxStatus;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.common.ulid.Ulid;
import com.gewu.domain.sandbox.Sandbox;
import com.gewu.sandbox.constant.SandboxConstants;
import com.gewu.common.dto.sandbox.CreateSandboxCommand;
import com.gewu.common.dto.sandbox.ExecCommandResponse;
import com.gewu.sandbox.security.CommandValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class DockerSandboxProvider implements SandboxProvider {

    private final DockerClient dockerClient;
    private final CommandValidator commandValidator;
    private final com.gewu.sandbox.security.DevCommandValidator devCommandValidator;

    @Value("${gewu.sandbox.defaults.image:gewu/sandbox-base:latest}")
    private String defaultImage;

    @Value("${gewu.sandbox.defaults.cpu:1}")
    private int defaultCpu;

    @Value("${gewu.sandbox.defaults.memory-mb:512}")
    private int defaultMemoryMb;

    @Value("${gewu.sandbox.defaults.disk-mb:1024}")
    private int defaultDiskMb;

    @Value("${gewu.sandbox.defaults.timeout-seconds:300}")
    private int defaultTimeoutSeconds;

    @Value("${gewu.sandbox.defaults.network-enabled:false}")
    private boolean defaultNetworkEnabled;

    @Override
    public Sandbox create(CreateSandboxCommand command) {
        String image = command.getImage() != null ? command.getImage() : defaultImage;
        int cpu = command.getCpuCores() != null ? command.getCpuCores() : defaultCpu;
        int memoryMb = command.getMemoryMb() != null ? command.getMemoryMb() : defaultMemoryMb;
        int diskMb = command.getDiskMb() != null ? command.getDiskMb() : defaultDiskMb;
        boolean networkEnabled = command.getNetworkEnabled() != null ? command.getNetworkEnabled() : defaultNetworkEnabled;
        int timeout = command.getTimeout() != null ? command.getTimeout() : defaultTimeoutSeconds;

        ensureImageExists(image);

        long memoryBytes = (long) memoryMb * 1024 * 1024;
        long nanoCpus = (long) cpu * 1_000_000_000L;

        List<String> env = new ArrayList<>();
        env.add("SANDBOX_TIMEOUT=" + timeout);

        var hostConfigBuilder = com.github.dockerjava.api.model.HostConfig.newHostConfig()
                .withNanoCPUs(nanoCpus)
                .withMemory(memoryBytes)
                .withNetworkMode(networkEnabled ? "bridge" : "none")
                .withSecurityOpts(List.of("no-new-privileges:true"))
                .withCapDrop(com.github.dockerjava.api.model.Capability.ALL)
                .withReadonlyRootfs(true)
                .withTmpFs(java.util.Map.of("/tmp", "rw,noexec,nosuid,size=64m"));

        // 工作空间卷挂载: 将用户持久化目录挂载到 /workspace
        if (command.getWorkspaceId() != null && !command.getWorkspaceId().isBlank()) {
            String volumeName = "gewu-ws-" + command.getWorkspaceId().substring(0, 12).toLowerCase();
            ensureVolumeExists(volumeName);
            hostConfigBuilder.withBinds(new com.github.dockerjava.api.model.Bind(
                    volumeName,
                    new com.github.dockerjava.api.model.Volume("/workspace"),
                    com.github.dockerjava.api.model.AccessMode.rw));
            // /workspace 需可写，关闭只读根文件系统
            hostConfigBuilder.withReadonlyRootfs(false);
            log.info("工作空间卷挂载: volume={}, workspaceId={}", volumeName, command.getWorkspaceId());
        }

        CreateContainerResponse container = dockerClient.createContainerCmd(image)
                .withName("gewu-sandbox-" + Ulid.next().substring(0, 8).toLowerCase())
                // 常驻进程覆盖镜像默认 CMD：沙箱为 exec/文件操作型容器，
                // 无 CMD 的基础镜像（如 alpine）启动后立即退出（S9 F4 实测）
                .withCmd("tail", "-f", "/dev/null")
                .withEnv(env)
                .withHostConfig(hostConfigBuilder)
                .exec();

        String sandboxId = Ulid.next();

        Sandbox sandbox = new Sandbox();
        sandbox.setId(sandboxId);
        sandbox.setContainerId(container.getId());
        sandbox.setSandboxName(command.getSandboxName());
        sandbox.setStatus(SandboxStatus.CREATING.getCode());
        sandbox.setImage(image);
        sandbox.setCpuLimit(cpu);
        sandbox.setMemoryLimitMb(memoryMb);
        sandbox.setDiskLimitMb(diskMb);
        sandbox.setNetworkEnabled(networkEnabled ? 1 : 0);
        sandbox.setTimeoutSeconds(timeout);
        sandbox.setRuntime("docker");

        log.info("创建沙箱容器: sandboxId={}, containerId={}, image={}", sandboxId, container.getId(), image);
        return sandbox;
    }

    @Override
    public void start(Sandbox sandbox) {
        dockerClient.startContainerCmd(sandbox.getContainerId()).exec();
        sandbox.setStatus(SandboxStatus.RUNNING.getCode());
        log.info("启动沙箱: sandboxId={}, containerId={}", sandbox.getId(), sandbox.getContainerId());
    }

    @Override
    public void stop(Sandbox sandbox) {
        dockerClient.stopContainerCmd(sandbox.getContainerId()).withTimeout(10).exec();
        sandbox.setStatus(SandboxStatus.STOPPED.getCode());
        log.info("停止沙箱: sandboxId={}, containerId={}", sandbox.getId(), sandbox.getContainerId());
    }

    @Override
    public void destroy(Sandbox sandbox) {
        try {
            dockerClient.removeContainerCmd(sandbox.getContainerId()).withForce(true).exec();
        } catch (Exception e) {
            log.warn("移除沙箱容器失败: containerId={}, error={}", sandbox.getContainerId(), e.getMessage());
        }
        sandbox.setStatus(SandboxStatus.DESTROYED.getCode());
        log.info("销毁沙箱: sandboxId={}, containerId={}", sandbox.getId(), sandbox.getContainerId());
    }

    @Override
    public ExecCommandResponse exec(Sandbox sandbox, String command, Integer timeoutSeconds) {
        // 命令安全校验 - 按 source 路由: dev 沙箱宽松，其他严格
        if ("dev".equals(sandbox.getSource())) {
            devCommandValidator.validate(command);
        } else {
            commandValidator.validate(command);
        }

        int timeout = timeoutSeconds != null ? timeoutSeconds : 30;

        ExecCreateCmdResponse execCreate = dockerClient.execCreateCmd(sandbox.getContainerId())
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withCmd("/bin/sh", "-c", command)
                .exec();

        ByteArrayOutputStream stdoutStream = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrStream = new ByteArrayOutputStream();

        long startTime = System.currentTimeMillis();

        try {
            dockerClient.execStartCmd(execCreate.getId())
                    .exec(new ExecStartResultCallback(stdoutStream, stderrStream))
                    .awaitCompletion(timeout, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("沙箱命令执行被中断: sandboxId={}", sandbox.getId());
        }

        long duration = System.currentTimeMillis() - startTime;

        // 获取真实退出码
        int exitCode = 0;
        try {
            com.github.dockerjava.api.command.InspectExecResponse execInspect =
                    dockerClient.inspectExecCmd(execCreate.getId()).exec();
            if (execInspect.getExitCodeLong() != null) {
                exitCode = execInspect.getExitCodeLong().intValue();
            }
        } catch (Exception e) {
            log.debug("获取退出码失败，默认 0: {}", e.getMessage());
        }

        return ExecCommandResponse.builder()
                .exitCode(exitCode)
                .stdout(stdoutStream.toString())
                .stderr(stderrStream.toString())
                .duration(duration)
                .build();
    }

    @Override
    public String getType() {
        return "docker";
    }

    private void ensureImageExists(String image) {
        try {
            dockerClient.inspectImageCmd(image).exec();
        } catch (NotFoundException e) {
            log.info("镜像 {} 不存在，开始拉取...", image);
            try {
                dockerClient.pullImageCmd(image)
                        .exec(new PullImageResultCallback())
                        .awaitCompletion(120, TimeUnit.SECONDS);
                log.info("镜像 {} 拉取完成", image);
            } catch (Exception pullException) {
                throw BusinessException.of(ResultCode.SANDBOX_IMAGE_PULL_FAILED,
                        "镜像拉取失败: " + image + " - " + pullException.getMessage());
            }
        }
    }

    /** 确保用户工作空间 Docker Volume 存在（幂等） */
    private void ensureVolumeExists(String volumeName) {
        try {
            dockerClient.createVolumeCmd().withName(volumeName).exec();
            log.debug("Docker volume 创建/已存在: {}", volumeName);
        } catch (Exception e) {
            // Volume 已存在时 Docker 会抛异常，忽略即可
            log.debug("Volume 已存在或创建失败: {} - {}", volumeName, e.getMessage());
        }
    }
}
