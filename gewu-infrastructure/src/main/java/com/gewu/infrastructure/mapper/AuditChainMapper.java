package com.gewu.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.domain.audit.AuditChainEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditChainMapper extends BaseMapper<AuditChainEntity> {
}