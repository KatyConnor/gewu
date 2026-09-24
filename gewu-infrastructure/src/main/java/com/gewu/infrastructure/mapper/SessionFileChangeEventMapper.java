package com.gewu.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.domain.session.SessionFileChangeEvent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SessionFileChangeEventMapper extends BaseMapper<SessionFileChangeEvent> {
}
