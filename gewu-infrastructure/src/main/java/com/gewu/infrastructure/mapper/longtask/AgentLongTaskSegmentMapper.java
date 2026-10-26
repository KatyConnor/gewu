package com.gewu.infrastructure.mapper.longtask;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.domain.longtask.AgentLongTaskSegment;
import org.apache.ibatis.annotations.Mapper;

/** 长任务分段执行记录 Mapper（docs/design/45）。 */
@Mapper
public interface AgentLongTaskSegmentMapper extends BaseMapper<AgentLongTaskSegment> {
}
