package com.gewu.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.domain.session.SessionContextEpoch;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SessionContextEpochMapper extends BaseMapper<SessionContextEpoch> {
}
