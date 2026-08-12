package com.gewu.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.domain.ai.ModelConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 模型配置 Mapper。
 *
 * @since 1.0.0
 */
@Mapper
public interface ModelConfigMapper extends BaseMapper<ModelConfig> {
}