package com.gewu.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.domain.workflow.WorkflowAuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WorkflowAuditLogMapper extends BaseMapper<WorkflowAuditLog> {
}
