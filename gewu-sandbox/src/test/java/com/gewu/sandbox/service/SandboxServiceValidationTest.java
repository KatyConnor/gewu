package com.gewu.sandbox.service;

import com.gewu.common.dto.sandbox.CreateSandboxCommand;
import com.gewu.domain.sandbox.Sandbox;
import com.gewu.infrastructure.mapper.SandboxAuditLogMapper;
import com.gewu.sandbox.audit.SandboxAuditWriter;
import com.gewu.sandbox.exception.SandboxResourceLimitExceededException;
import com.gewu.sandbox.mapper.SandboxMapper;
import com.gewu.sandbox.provider.SandboxProvider;
import com.gewu.sandbox.provider.SandboxProviderFactory;
import com.gewu.sandbox.template.SandboxTemplateMatcher;
import com.gewu.sandbox.validator.SandboxValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 沙箱创建准入校验回归测试（P1）：白名单外镜像拒绝、资源上限强制、dev 镜像放行。
 */
@ExtendWith(MockitoExtension.class)
class SandboxServiceValidationTest {

    @Mock SandboxMapper sandboxMapper;
    @Mock SandboxAuditLogMapper auditLogMapper;
    @Mock SandboxProviderFactory providerFactory;
    @Mock SandboxTemplateMatcher templateMatcher;
    @Mock SandboxAuditWriter auditWriter;
    @Mock SandboxProvider provider;

    private SandboxService service;

    @BeforeEach
    void setUp() {
        service = new SandboxService(sandboxMapper, auditLogMapper, providerFactory,
                templateMatcher, auditWriter, new SandboxValidator());
    }

    private CreateSandboxCommand command(String image, Integer cpu, Integer mem, Integer disk, Integer timeout) {
        CreateSandboxCommand c = new CreateSandboxCommand();
        c.setImage(image);
        c.setCpuCores(cpu);
        c.setMemoryMb(mem);
        c.setDiskMb(disk);
        c.setTimeout(timeout);
        return c;
    }

    @Test
    @DisplayName("白名单外镜像拒绝")
    void rejectUnknownImage() {
        assertThrows(SandboxResourceLimitExceededException.class,
                () -> service.createSandbox(command("evil/image:v1", 1, 512, 1024, 300)));
    }

    @Test
    @DisplayName("dev 镜像与 dev 档资源放行")
    void acceptDevImageAndResource() {
        when(providerFactory.getDefaultProvider()).thenReturn(provider);
        Sandbox sandbox = new Sandbox();
        sandbox.setId("sb-1");
        sandbox.setStatus("creating");
        when(provider.create(any())).thenReturn(sandbox);

        assertNotNull(service.createSandbox(command("gewu/dev-base:latest", 2, 4096, 20480, 300)));
    }

    @Test
    @DisplayName("CPU 超上限拒绝")
    void rejectCpuOverLimit() {
        assertThrows(SandboxResourceLimitExceededException.class,
                () -> service.createSandbox(command("gewu/dev-base:latest", 9, 512, 1024, 300)));
    }

    @Test
    @DisplayName("内存超上限拒绝")
    void rejectMemoryOverLimit() {
        assertThrows(SandboxResourceLimitExceededException.class,
                () -> service.createSandbox(command("gewu/dev-base:latest", 1, 32768, 1024, 300)));
    }
}
