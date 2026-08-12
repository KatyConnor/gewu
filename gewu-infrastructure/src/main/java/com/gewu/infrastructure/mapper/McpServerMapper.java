package com.gewu.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import com.gewu.domain.agent.McpServer;

@Mapper
public interface McpServerMapper extends BaseMapper<McpServer> {
}
