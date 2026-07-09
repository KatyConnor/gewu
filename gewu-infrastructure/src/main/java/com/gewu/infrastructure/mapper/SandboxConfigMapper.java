package com.gewu.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.domain.sandbox.SandboxConfig;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SandboxConfigMapper extends BaseMapper<SandboxConfig> {
}
