package com.gewu.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.domain.session.SessionMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SessionMessageMapper extends BaseMapper<SessionMessage> {
}
