package com.gewu.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.common.dto.sandbox.SandboxAuditDTO;
import com.gewu.domain.sandbox.SandboxAuditLog;
import com.gewu.infrastructure.mapper.SandboxAuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 沙箱审计查询服务（管理端，共库直查 sandbox_audit_log；写入仍在 gewu-sandbox 进程）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminSandboxAuditService {

    private final SandboxAuditLogMapper auditLogMapper;

    /** 获取指定沙箱的审计日志 */
    public List<SandboxAuditDTO> getAuditLogsBySandboxId(String sandboxId) {
        LambdaQueryWrapper<SandboxAuditLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SandboxAuditLog::getSandboxId, sandboxId)
               .orderByDesc(SandboxAuditLog::getTimestamp);
        return auditLogMapper.selectList(wrapper).stream()
                .map(this::toDTO)
                .toList();
    }

    /** 获取所有审计日志 */
    public List<SandboxAuditDTO> getAllAuditLogs() {
        LambdaQueryWrapper<SandboxAuditLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(SandboxAuditLog::getTimestamp);
        return auditLogMapper.selectList(wrapper).stream()
                .map(this::toDTO)
                .toList();
    }

    private SandboxAuditDTO toDTO(SandboxAuditLog entity) {
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
