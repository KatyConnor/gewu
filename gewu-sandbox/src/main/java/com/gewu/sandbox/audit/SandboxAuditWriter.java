package com.gewu.sandbox.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.common.crypto.SM3Util;
import com.gewu.common.ulid.Ulid;
import com.gewu.domain.sandbox.SandboxAuditLog;
import com.gewu.infrastructure.mapper.SandboxAuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * 沙箱审计统一写入方（SM3 哈希链）。
 * <p>REQUIRES_NEW：FAIL 审计必须在外层事务回滚后仍留存；前驱查询与写入在
 * 同一独立事务内完成。进程内锁保证单实例部署下的链连续；多实例部署存在链分叉
 * 风险（当前沙箱服务单实例部署，扩展时需引入分布式锁）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SandboxAuditWriter {

    private final SandboxAuditLogMapper auditLogMapper;

    private final ReentrantLock chainLock = new ReentrantLock();

    /** 凭证 URL 脱敏：https://user:token@host → https://***@host */
    private static final Pattern CREDENTIAL_PATTERN = Pattern.compile("://[^/@\\s]+:[^/@\\s]+@");

    /**
     * 链式写入一条审计记录（result: SUCCESS/FAIL）。任何失败仅记日志不抛出——审计不阻断主链路。
     */
    public void append(String sandboxId, String action, String userId, String details, String result) {
        try {
            chainLock.lock();
            try {
                SandboxAuditLog prev = auditLogMapper.selectOne(new LambdaQueryWrapper<SandboxAuditLog>()
                        .orderByDesc(SandboxAuditLog::getTimestamp)
                        .last("LIMIT 1"));
                String prevHash = prev != null && prev.getLogHash() != null ? prev.getLogHash() : "";
                long ts = Instant.now().toEpochMilli();
                String safeDetails = maskCredential(details);
                SandboxAuditLog auditLog = new SandboxAuditLog();
                auditLog.setId(Ulid.next());
                auditLog.setSandboxId(sandboxId);
                auditLog.setAction(action);
                auditLog.setUserId(userId);
                auditLog.setResource("sandbox");
                auditLog.setResult(result);
                auditLog.setDetails(safeDetails);
                auditLog.setTimestamp(ts);
                auditLog.setCreatedAt(ts);
                auditLog.setLogHash(SM3Util.hashHex(prevHash + "|" + sandboxId + "|" + action
                        + "|" + userId + "|" + safeDetails + "|" + result + "|" + ts));
                auditLogMapper.insert(auditLog);
            } finally {
                chainLock.unlock();
            }
        } catch (Exception e) {
            log.error("沙箱审计日志写入失败: sandboxId={}, action={}", sandboxId, action, e);
        }
    }

    /** 审计脱敏：命令/URL 中可能包含 token 形式的凭证（git credential store 等） */
    static String maskCredential(String details) {
        return details == null ? null : CREDENTIAL_PATTERN.matcher(details).replaceAll("://***@");
    }
}
