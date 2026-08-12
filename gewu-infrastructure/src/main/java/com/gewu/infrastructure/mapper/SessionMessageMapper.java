package com.gewu.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.domain.session.SessionMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SessionMessageMapper extends BaseMapper<SessionMessage> {

    /**
     * 查询指定会话的最大消息序列号。
     * 用于 appendChatInteraction 时计算 nextSeq，避免基于 session.messageCount 的非原子计数器
     * 在并发或前端已预先保存消息时产生主键冲突。
     */
    @Select("SELECT COALESCE(MAX(seq), 0) FROM session_message WHERE session_id = #{sessionId}")
    Integer selectMaxSeq(@Param("sessionId") String sessionId);
}
