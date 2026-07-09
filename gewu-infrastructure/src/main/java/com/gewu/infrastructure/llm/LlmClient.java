package com.gewu.infrastructure.llm;

import reactor.core.publisher.Flux;

public interface LlmClient {

    String getProvider();

    LlmResponse chat(LlmRequest request);

    Flux<LlmChunk> chatStream(LlmRequest request);
}
