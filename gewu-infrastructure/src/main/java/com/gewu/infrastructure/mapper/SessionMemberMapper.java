package com.gewu.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.domain.session.SessionMember;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SessionMemberMapper extends BaseMapper<SessionMember> {
}
