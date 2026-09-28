package com.veloflow.engine.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.veloflow.engine.persistence.model.WorkflowEventSubscription;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WorkflowEventSubscriptionMapper extends BaseMapper<WorkflowEventSubscription> {
}
