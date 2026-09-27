package com.veloflow.engine.identity;

import java.util.Map;
import java.util.Set;

/** 默认身份实现（无宿主接入时）：system 透传 */
public class DefaultFlowIdentityProvider implements FlowIdentityProvider {

    @Override
    public String currentUserId() {
        return "system";
    }

    @Override
    public String currentUsername() {
        return "system";
    }

    @Override
    public Map<String, String> batchUserNames(Set<String> userIds) {
        return Map.of();
    }
}
