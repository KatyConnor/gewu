package com.gewu.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.domain.governance.GovernancePolicyEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface GovernancePolicyMapper extends BaseMapper<GovernancePolicyEntity> {
}
