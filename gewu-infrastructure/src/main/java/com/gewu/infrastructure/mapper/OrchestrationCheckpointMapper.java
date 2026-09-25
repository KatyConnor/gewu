package com.gewu.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.domain.orchestration.OrchestrationCheckpointEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrchestrationCheckpointMapper extends BaseMapper<OrchestrationCheckpointEntity> {
}
