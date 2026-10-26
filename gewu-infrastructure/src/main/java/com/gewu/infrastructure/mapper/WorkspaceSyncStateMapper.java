package com.gewu.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.domain.workspace.WorkspaceSyncState;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工作空间双向同步状态 Mapper（设计文档 42 项三）。
 */
@Mapper
public interface WorkspaceSyncStateMapper extends BaseMapper<WorkspaceSyncState> {
}
