package com.gewu.infrastructure.wenshi.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

/**
 * 基于 LLM 服务商原生 Embedding API 的嵌入适配器。
 * <p>
 * 当前支持 DeepSeek text-embedding-v1 模型，通过 HTTP 调用远程 embedding 接口。
 * 适用于本地模型不可用或需要更高维度语义表达的场景。
 * <p>
 * 容错策略：API 调用失败时返回零向量，不向上抛出异常。
 *
 * @since 1.0.0
 */
@Slf4j
public class LlmNativeEmbeddingAdapter implements EmbeddingAdapter {

    /** 嵌入向量维度，DeepSeek text-embedding-v1 默认为 1024 */
    @Value("${wenshi.embedding.dimension:1024}")
    private int dimension;

    /** DeepSeek API Key */
    @Value("${gewu.ai.deepseek.api-key:}")
    private String deepseekApiKey;

    /** DeepSeek 基础 URL，默认指向 chat/completions，内部替换为 embeddings */
    @Value("${gewu.ai.deepseek.base-url:https://api.deepseek.com/v1/chat/completions}")
    private String deepseekBaseUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * 构造 LLM 原生嵌入适配器。
     *
     * @param dimension 嵌入向量维度
     */
    public LlmNativeEmbeddingAdapter(int dimension) {
        this.dimension = dimension;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 调用 DeepSeek embedding API，失败时返回零向量。
     *
     * @param text 待编码文本，不可为 null 或空白
     * @return 嵌入向量；若调用失败则返回全零向量
     */
    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return new float[dimension];
        }
        try {
            // 将 chat/completions 路径替换为 embeddings 路径
            String embeddingUrl = deepseekBaseUrl.replace("chat/completions", "embeddings");
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", "text-embedding-v1");
            body.put("input", text);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(embeddingUrl))
                    .header("Authorization", "Bearer " + deepseekApiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode data = root.path("data");
            if (data.isArray() && !data.isEmpty()) {
                JsonNode arr = data.get(0).path("embedding");
                float[] result = new float[arr.size()];
                for (int i = 0; i < arr.size(); i++) {
                    result[i] = (float) arr.get(i).asDouble();
                }
                return result;
            }
        } catch (Exception e) {
            // 静默降级，避免影响上层业务
            log.debug("LlmNativeEmbeddingAdapter embed failed, returning zero vector: {}", e.getMessage());
        }
        return new float[dimension];
    }

    /**
     * {@inheritDoc}
     * <p>
     * 当前实现为逐条调用 API，后续可优化为批量请求以减少网络开销。
     */
    @Override
    public float[][] embedBatch(List<String> texts) {
        float[][] results = new float[texts.size()][];
        for (int i = 0; i < texts.size(); i++) {
            results[i] = embed(texts.get(i));
        }
        return results;
    }

    @Override
    public int getDimension() {
        return dimension;
    }

    @Override
    public String getProvider() {
        return "llm-native";
    }
}
