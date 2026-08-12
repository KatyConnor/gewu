package com.gewu.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.domain.requirement.Requirement;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RequirementMapper extends BaseMapper<Requirement> {
}
