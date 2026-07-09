package com.gewu.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.domain.agent.AgentExecution;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentExecutionMapper extends BaseMapper<AgentExecution> {
}
