package com.gewu.application.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.common.ulid.Ulid;
import com.gewu.domain.audit.AuditChainEntity;
import com.gewu.infrastructure.mapper.AuditChainMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;

/**
 * WORM 审计链服务 - 链式哈希保证审计记录不可篡改。
 * <p>每条记录的 hash_current = SHA-256(hash_previous + payload + timestamp)。
 * 验证时按链顺序逐条重算哈希，任一篡改即断裂。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditChainService {

    private final AuditChainMapper auditChainMapper;
    private final ObjectMapper objectMapper;

    /** 创世哈希（链头） */
    private static final String GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000";

    /**
     * 追加一条审计记录到链尾。
     *
     * @param eventType     事件类型
     * @param executionId   执行实例 ID
     * @param actor         执行者
     * @param action        动作
     * @param decisionTrace 决策轨迹 JSON
     * @return 创建的审计记录
     */
    public AuditChainEntity append(String eventType, String executionId, String actor,
                                    String action, String decisionTrace) {
        // 获取链尾哈希
        String previousHash = getLatestHash();

        // 构建审计记录
        AuditChainEntity entity = new AuditChainEntity();
        entity.setId(Ulid.next());
        entity.setEventType(eventType);
        entity.setExecutionId(executionId);
        entity.setActor(actor);
        entity.setAction(action);
        entity.setDecisionTrace(decisionTrace);
        entity.setHashPrevious(previousHash);
        entity.setVerified(0);
        long now = Instant.now().toEpochMilli();
        entity.setCreatedAt(now);

        // 计算当前记录哈希: SHA-256(previousHash + payload + timestamp)
        String currentHash = computeHash(previousHash, eventType, executionId, actor, action, decisionTrace, now);
        entity.setHashCurrent(currentHash);

        auditChainMapper.insert(entity);
        log.info("AuditChain.append: id={}, eventType={}, actor={}, action={}, hash={}",
                entity.getId(), eventType, actor, action, currentHash.substring(0, 16));
        return entity;
    }

    /**
     * 验证审计链完整性 - 逐条重算哈希，检测篡改。
     *
     * @return 验证通过返回 true，发现篡改返回 false
     */
    public boolean verifyChain() {
        List<AuditChainEntity> chain = auditChainMapper.selectList(
                new LambdaQueryWrapper<AuditChainEntity>()
                        .orderByAsc(AuditChainEntity::getCreatedAt));

        if (chain.isEmpty()) return true;

        String expectedPrevious = GENESIS_HASH;
        for (AuditChainEntity record : chain) {
            // 验证前驱哈希链接
            if (!expectedPrevious.equals(record.getHashPrevious())) {
                log.error("AuditChain.verify: 链断裂! id={}, expected={}, actual={}",
                        record.getId(), expectedPrevious, record.getHashPrevious());
                return false;
            }
            // 重算当前记录哈希
            String recomputed = computeHash(
                    record.getHashPrevious(), record.getEventType(), record.getExecutionId(),
                    record.getActor(), record.getAction(), record.getDecisionTrace(), record.getCreatedAt());
            if (!recomputed.equals(record.getHashCurrent())) {
                log.error("AuditChain.verify: 哈希不匹配! id={}, expected={}, actual={}",
                        record.getId(), recomputed, record.getHashCurrent());
                return false;
            }
            // 标记已验证
            record.setVerified(1);
            auditChainMapper.updateById(record);
            expectedPrevious = record.getHashCurrent();
        }
        log.info("AuditChain.verify: 链验证通过, 共 {} 条记录", chain.size());
        return true;
    }

    /**
     * 查询执行实例的审计记录。
     */
    public List<AuditChainEntity> queryByExecution(String executionId) {
        return auditChainMapper.selectList(
                new LambdaQueryWrapper<AuditChainEntity>()
                        .eq(AuditChainEntity::getExecutionId, executionId)
                        .orderByAsc(AuditChainEntity::getCreatedAt));
    }

    /**
     * 查询全部审计记录（分页）。
     */
    public List<AuditChainEntity> queryAll(int limit) {
        return auditChainMapper.selectList(
                new LambdaQueryWrapper<AuditChainEntity>()
                        .orderByDesc(AuditChainEntity::getCreatedAt)
                        .last("LIMIT " + limit));
    }

    /**
     * 获取链尾最新哈希。
     */
    private String getLatestHash() {
        AuditChainEntity latest = auditChainMapper.selectOne(
                new LambdaQueryWrapper<AuditChainEntity>()
                        .orderByDesc(AuditChainEntity::getCreatedAt)
                        .last("LIMIT 1"));
        return latest != null ? latest.getHashCurrent() : GENESIS_HASH;
    }

    /**
     * 计算 SHA-256 链式哈希。
     */
    private String computeHash(String previousHash, String eventType, String executionId,
                                String actor, String action, String decisionTrace, long timestamp) {
        try {
            String payload = String.join("|",
                    previousHash != null ? previousHash : GENESIS_HASH,
                    eventType != null ? eventType : "",
                    executionId != null ? executionId : "",
                    actor != null ? actor : "",
                    action != null ? action : "",
                    decisionTrace != null ? decisionTrace : "",
                    String.valueOf(timestamp));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 哈希计算失败", e);
        }
    }
}