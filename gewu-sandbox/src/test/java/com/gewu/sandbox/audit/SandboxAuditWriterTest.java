package com.gewu.sandbox.audit;

import com.gewu.common.crypto.SM3Util;
import com.gewu.domain.sandbox.SandboxAuditLog;
import com.gewu.infrastructure.mapper.SandboxAuditLogMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计写入器回归测试（P1）：SM3 哈希链与凭证脱敏。
 */
@ExtendWith(MockitoExtension.class)
class SandboxAuditWriterTest {

    @Mock
    SandboxAuditLogMapper auditLogMapper;

    @Test
    @DisplayName("凭证 URL 脱敏为 ://***@")
    void maskCredentialUrl() {
        assertEquals("git clone https://***@github.com/a.git",
                SandboxAuditWriter.maskCredential("git clone https://user:tkn@github.com/a.git"));
    }

    @Test
    @DisplayName("普通 URL 不脱敏")
    void keepPlainUrl() {
        assertEquals("https://github.com/a", SandboxAuditWriter.maskCredential("https://github.com/a"));
    }

    @Test
    @DisplayName("首条记录的哈希以空前缀计算")
    void firstRecordHashChainedFromEmpty() {
        SandboxAuditWriter writer = new SandboxAuditWriter(auditLogMapper);
        when(auditLogMapper.selectOne(any())).thenReturn(null);

        writer.append("sb-1", "COMMAND", "u-1", "执行命令: ls", "SUCCESS");

        ArgumentCaptor<SandboxAuditLog> captor = ArgumentCaptor.forClass(SandboxAuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        SandboxAuditLog inserted = captor.getValue();
        assertEquals(
                SM3Util.hashHex("|sb-1|COMMAND|u-1|执行命令: ls|SUCCESS|" + inserted.getTimestamp()),
                inserted.getLogHash());
    }

    @Test
    @DisplayName("第二条记录的哈希链接续前条且脱敏后参与计算")
    void secondRecordChainsFromFirst() {
        SandboxAuditWriter writer = new SandboxAuditWriter(auditLogMapper);
        when(auditLogMapper.selectOne(any())).thenReturn(null);

        writer.append("sb-1", "COMMAND", "u-1", "第一条", "SUCCESS");
        ArgumentCaptor<SandboxAuditLog> captor = ArgumentCaptor.forClass(SandboxAuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        SandboxAuditLog first = captor.getValue();

        when(auditLogMapper.selectOne(any())).thenReturn(first);
        writer.append("sb-1", "COMMAND", "u-1", "git clone https://u:tkn@host/x", "FAIL");
        ArgumentCaptor<SandboxAuditLog> secondCaptor = ArgumentCaptor.forClass(SandboxAuditLog.class);
        verify(auditLogMapper, times(2)).insert(secondCaptor.capture());
        SandboxAuditLog second = secondCaptor.getAllValues().get(1);

        assertEquals(
                SM3Util.hashHex(first.getLogHash() + "|sb-1|COMMAND|u-1|git clone https://***@host/x|FAIL|"
                        + second.getTimestamp()),
                second.getLogHash());
    }
}
