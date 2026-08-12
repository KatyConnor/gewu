package com.gewu.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.domain.ai.ModelProvider;
import org.apache.ibatis.annotations.Mapper;

/**
 * 模型供应商 Mapper。
 *
 * @since 1.0.0
 */
@Mapper
public interface ModelProviderMapper extends BaseMapper<ModelProvider> {
}