package com.gewu.infrastructure.mapper.longtask;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.domain.longtask.AgentLongTaskReview;
import org.apache.ibatis.annotations.Mapper;

/** 长任务人工审核 Mapper（docs/design/45）。 */
@Mapper
public interface AgentLongTaskReviewMapper extends BaseMapper<AgentLongTaskReview> {
}
