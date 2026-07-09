package com.gewu.sandbox.provider;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.gewu.common.enums.SandboxStatus;
import com.gewu.common.ulid.Ulid;
import com.gewu.domain.sandbox.Sandbox;
import com.gewu.sandbox.constant.SandboxConstants;
import com.gewu.sandbox.dto.CreateSandboxCommand;
import com.gewu.sandbox.dto.ExecCommandResponse;
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
        int cpu = command.getCpuLimit() != null ? command.getCpuLimit() : defaultCpu;
        int memoryMb = command.getMemoryLimit() != null ? command.getMemoryLimit() : defaultMemoryMb;
        int diskMb = command.getDiskLimit() != null ? command.getDiskLimit() : defaultDiskMb;
        boolean networkEnabled = command.getNetworkEnabled() != null ? command.getNetworkEnabled() : defaultNetworkEnabled;
        int timeout = command.getTimeout() != null ? command.getTimeout() : defaultTimeoutSeconds;

        long memoryBytes = (long) memoryMb * 1024 * 1024;
        long nanoCpus = (long) cpu * 1_000_000_000L;

        List<String> env = new ArrayList<>();
        env.add("SANDBOX_TIMEOUT=" + timeout);

        CreateContainerResponse container = dockerClient.createContainerCmd(image)
                .withName("gewu-sandbox-" + Ulid.next().substring(0, 8).toLowerCase())
                .withEnv(env)
                .withHostConfig(com.github.dockerjava.api.model.HostConfig.newHostConfig()
                        .withNanoCPUs(nanoCpus)
                        .withMemory(memoryBytes)
                        .withNetworkMode(networkEnabled ? "bridge" : "none")
                        .withSecurityOpts(List.of("no-new-privileges:true"))
                        .withCapDrop(com.github.dockerjava.api.model.Capability.ALL)
                        .withReadonlyRootfs(true)
                        .withTmpFs(java.util.Map.of("/tmp", "rw,noexec,nosuid,size=64m")))
                .exec();

        String sandboxId = Ulid.next();

        Sandbox sandbox = new Sandbox();
        sandbox.setId(sandboxId);
        sandbox.setContainerId(container.getId());
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

        return ExecCommandResponse.builder()
                .exitCode(0)
                .stdout(stdoutStream.toString())
                .stderr(stderrStream.toString())
                .duration(duration)
                .build();
    }

    @Override
    public String getType() {
        return "docker";
    }
}
