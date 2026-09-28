package com.veloflow.engine.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.veloflow.engine.persistence.model.WorkflowVersion;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WorkflowVersionMapper extends BaseMapper<WorkflowVersion> {
}
