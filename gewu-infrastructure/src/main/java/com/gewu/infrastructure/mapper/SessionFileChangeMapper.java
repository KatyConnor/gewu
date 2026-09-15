package com.gewu.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.domain.session.SessionFileChange;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会话文件变更记录 Mapper（S9 F3）。
 */
@Mapper
public interface SessionFileChangeMapper extends BaseMapper<SessionFileChange> {
}
