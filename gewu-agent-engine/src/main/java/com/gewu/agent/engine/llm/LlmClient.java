package com.gewu.agent.engine.llm;

import com.gewu.agent.engine.llm.model.LlmChunk;
import com.gewu.agent.engine.llm.model.LlmRequest;
import com.gewu.agent.engine.llm.model.LlmResponse;
import reactor.core.publisher.Flux;

/**
 * LLM 客户端抽象接口。
 * <p>所有大语言模型供应商的客户端实现此接口。框架提供 {@link OpenAiCompatibleClient} 通用实现，
 * 覆盖所有遵循 OpenAI Chat Completions 协议的供应商。使用方也可实现此接口对接私有协议模型。
 *
 * @since 1.0.0
 */
public interface LlmClient {

    /** 供应商标识，如 qwen / deepseek / zhipu */
    String getProvider();

    /** 同步对话：发送请求并阻塞等待完整响应 */
    LlmResponse chat(LlmRequest request);

    /** 流式对话：返回增量分块流，适配 SSE 实时推送 */
    Flux<LlmChunk> chatStream(LlmRequest request);
}
