package com.gewu.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.domain.orchestration.OrchestrationWebhookEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrchestrationWebhookMapper extends BaseMapper<OrchestrationWebhookEntity> {
}
