package com.gewu.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.domain.requirement.RequirementComment;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RequirementCommentMapper extends BaseMapper<RequirementComment> {
}
