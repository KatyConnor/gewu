package com.gewu.infrastructure.mapper.longtask;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.domain.longtask.AgentLongTask;
import org.apache.ibatis.annotations.Mapper;

/** 持久化目标驱动长任务 Mapper（docs/design/45）。 */
@Mapper
public interface AgentLongTaskMapper extends BaseMapper<AgentLongTask> {
}
