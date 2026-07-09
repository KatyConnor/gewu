package com.gewu.infrastructure.llm;

import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class LlmClientFactory {

    private final Map<String, LlmClient> clientMap;

    public LlmClient getClient(String provider) {
        LlmClient client = clientMap.get(provider);
        if (client == null) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "不支持的 LLM 提供商: " + provider);
        }
        return client;
    }
}
