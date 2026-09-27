package com.veloflow.engine.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.veloflow.engine.persistence.model.WorkflowAuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WorkflowAuditLogMapper extends BaseMapper<WorkflowAuditLog> {
}
