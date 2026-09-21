package com.gewu.sandbox.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.common.enums.SandboxStatus;
import com.gewu.domain.sandbox.Sandbox;
import com.gewu.sandbox.mapper.SandboxMapper;
import com.gewu.sandbox.provider.SandboxProvider;
import com.gewu.sandbox.provider.SandboxProviderFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxScheduler {

    private final SandboxMapper sandboxMapper;
    private final SandboxProviderFactory providerFactory;

    @Value("${gewu.sandbox.lifecycle.idle-timeout-minutes:30}")
    private int idleTimeoutMinutes;

    @Value("${gewu.sandbox.lifecycle.auto-stop-enabled:true}")
    private boolean autoStopEnabled;

    @Value("${gewu.sandbox.lifecycle.agent-max-lifetime-seconds:300}")
    private int agentMaxLifetimeSeconds;

    @Value("${gewu.sandbox.dev.idle-timeout-minutes:120}")
    private int devIdleTimeoutMinutes;

    @Scheduled(fixedRate = 300000)
    public void autoStopIdleSandboxes() {
        if (!autoStopEnabled) return;

        // dev 沙箱空闲阈值独立分档（dev.idle-timeout-minutes，默认 120 分钟），其余用默认档
        long now = Instant.now().toEpochMilli();
        List<Sandbox> runningSandboxes = sandboxMapper.selectList(
                new LambdaQueryWrapper<Sandbox>()
                        .eq(Sandbox::getStatus, SandboxStatus.RUNNING.getCode())
                        .ne(Sandbox::getSource, "agent")
                        .isNotNull(Sandbox::getLastUsedAt))
                .stream()
                .filter(s -> s.getLastUsedAt() < now - idleThresholdOf(s.getSource()))
                .toList();

        if (runningSandboxes.isEmpty()) return;

        log.info("空闲自动停止: 发现 {} 个超时沙箱", runningSandboxes.size());
        SandboxProvider provider = providerFactory.getDefaultProvider();

        for (Sandbox sandbox : runningSandboxes) {
            try {
                provider.stop(sandbox);
                sandbox.setStoppedAt(Instant.now().toEpochMilli());
                sandboxMapper.updateById(sandbox);
                log.info("空闲自动停止沙箱: sandboxId={}, lastUsedAt={}",
                        sandbox.getId(), sandbox.getLastUsedAt());
            } catch (Exception e) {
                log.warn("空闲自动停止失败: sandboxId={}, error={}", sandbox.getId(), e.getMessage());
            }
        }
    }

    /** 空闲判定阈值（毫秒）：dev 沙箱走独立档位，其余用默认档 */
    private long idleThresholdOf(String source) {
        long minutes = "dev".equals(source) ? devIdleTimeoutMinutes : idleTimeoutMinutes;
        return java.time.Duration.ofMinutes(minutes).toMillis();
    }

    @Scheduled(fixedRate = 3600000)
    public void checkExpiredSandboxes() {
        long now = Instant.now().toEpochMilli();
        List<Sandbox> expiredSandboxes = sandboxMapper.selectList(
                new LambdaQueryWrapper<Sandbox>()
                        .isNotNull(Sandbox::getExpireAt)
                        .lt(Sandbox::getExpireAt, now)
                        .ne(Sandbox::getStatus, SandboxStatus.DESTROYED.getCode()));

        if (expiredSandboxes.isEmpty()) return;

        log.info("过期检查: 发现 {} 个过期沙箱", expiredSandboxes.size());
        SandboxProvider provider = providerFactory.getDefaultProvider();

        for (Sandbox sandbox : expiredSandboxes) {
            try {
                if (SandboxStatus.RUNNING.getCode().equals(sandbox.getStatus())) {
                    provider.stop(sandbox);
                }
                sandbox.setStatus(SandboxStatus.EXPIRED.getCode());
                sandboxMapper.updateById(sandbox);
                log.info("沙箱已标记过期: sandboxId={}, expireAt={}", sandbox.getId(), sandbox.getExpireAt());
            } catch (Exception e) {
                log.warn("过期处理失败: sandboxId={}, error={}", sandbox.getId(), e.getMessage());
            }
        }
    }

    @Scheduled(fixedRate = 60000)
    public void autoDestroyAgentSandboxes() {
        long maxLifetime = Instant.now().minus(java.time.Duration.ofSeconds(agentMaxLifetimeSeconds)).toEpochMilli();
        List<Sandbox> agentSandboxes = sandboxMapper.selectList(
                new LambdaQueryWrapper<Sandbox>()
                        .eq(Sandbox::getSource, "agent")
                        .eq(Sandbox::getAutoDestroy, 1)
                        .ne(Sandbox::getStatus, SandboxStatus.DESTROYED.getCode())
                        .isNotNull(Sandbox::getStartedAt)
                        .lt(Sandbox::getStartedAt, maxLifetime));

        if (agentSandboxes.isEmpty()) return;

        log.info("Agent 沙箱超时销毁: 发现 {} 个超时沙箱", agentSandboxes.size());
        SandboxProvider provider = providerFactory.getDefaultProvider();

        for (Sandbox sandbox : agentSandboxes) {
            try {
                provider.destroy(sandbox);
                sandboxMapper.updateById(sandbox);
                log.info("Agent 沙箱自动销毁: sandboxId={}, startedAt={}",
                        sandbox.getId(), sandbox.getStartedAt());
            } catch (Exception e) {
                log.warn("Agent 沙箱销毁失败: sandboxId={}, error={}", sandbox.getId(), e.getMessage());
            }
        }
    }
}