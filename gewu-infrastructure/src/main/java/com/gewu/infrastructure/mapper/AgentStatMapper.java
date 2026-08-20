package com.gewu.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.domain.agent.AgentStatEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentStatMapper extends BaseMapper<AgentStatEntity> {
}