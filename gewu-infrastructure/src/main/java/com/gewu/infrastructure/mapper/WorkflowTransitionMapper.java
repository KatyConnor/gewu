package com.gewu.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.domain.workflow.WorkflowTransition;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WorkflowTransitionMapper extends BaseMapper<WorkflowTransition> {
}
