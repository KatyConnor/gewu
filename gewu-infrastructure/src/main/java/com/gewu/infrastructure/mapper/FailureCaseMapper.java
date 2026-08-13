package com.gewu.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.domain.orchestration.FailureCaseEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FailureCaseMapper extends BaseMapper<FailureCaseEntity> {
}