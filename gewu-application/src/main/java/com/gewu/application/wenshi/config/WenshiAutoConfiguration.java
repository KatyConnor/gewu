package com.gewu.application.wenshi.config;

import com.gewu.infrastructure.wenshi.adapter.BgeSmallEmbeddingAdapter;
import com.gewu.infrastructure.wenshi.adapter.EmbeddingAdapter;
import com.gewu.infrastructure.wenshi.adapter.LlmNativeEmbeddingAdapter;
import com.gewu.infrastructure.wenshi.adapter.PgvectorAdapter;
import com.gewu.infrastructure.wenshi.adapter.VectorStoreAdapter;
import com.gewu.infrastructure.wenshi.BgeTokenizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Wenshi 适配器 Bean 配置 — 始终创建嵌入和存储适配器，不受 enabled 开关控制。
 * <p>
 * 该配置类无条件生效，确保 {@link EmbeddingAdapter} 和 {@link VectorStoreAdapter}
 * 在任何模式下都可用，避免 {@code wenshi.enabled=false} 时依赖注入失败。
 *
 * @since 1.0.0
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(WenshiProperties.class)
public class WenshiAutoConfiguration {

    /**
     * 创建向量嵌入适配器 Bean。
     * <p>
     * 根据 {@code wenshi.embedding.adapter} 配置选择实现：
     * <ul>
     *   <li>bge-small：本地 ONNX 推理（默认）</li>
     *   <li>llm-native：调用 LLM 服务商 embedding API</li>
     * </ul>
     * 内部实现类不注册为独立 Bean，避免注入歧义。
     *
     * @param properties      问石配置属性
     * @param wenshiTokenizer BGE 分词器
     * @return 嵌入适配器实例
     * @since 1.0.0
     */
    @Bean
    @ConditionalOnMissingBean
    public EmbeddingAdapter embeddingAdapter(WenshiProperties properties,
                                              BgeTokenizer wenshiTokenizer) {
        String adapter = properties.getEmbedding().getAdapter();
        log.info("Wenshi: initializing EmbeddingAdapter={}", adapter);

        switch (adapter) {
            case "bge-small":
                return new BgeSmallEmbeddingAdapter(wenshiTokenizer);
            case "llm-native":
                return new LlmNativeEmbeddingAdapter(properties.getEmbedding().getDimension());
            default:
                log.warn("Wenshi: unknown embedding adapter '{}', falling back to llm-native", adapter);
                return new LlmNativeEmbeddingAdapter(properties.getEmbedding().getDimension());
        }
    }

    /**
     * 创建向量存储适配器 Bean。
     * <p>
     * 根据 {@code wenshi.vectorstore.adapter} 配置选择实现，默认 pgvector。
     *
     * @param properties 问石配置属性
     * @param holder     问石数据源持有者
     * @return 向量存储适配器实例
     * @since 1.0.0
     */
    @Bean
    @ConditionalOnBean(name = "wenshiDataSourceHolder")
    @ConditionalOnMissingBean
    public VectorStoreAdapter vectorStoreAdapter(WenshiProperties properties,
                                                  WenshiDataSourceConfig.WenshiDataSourceHolder holder) {
        String adapter = properties.getVectorstore().getAdapter();
        log.info("Wenshi: initializing VectorStoreAdapter={}", adapter);

        if ("pgvector".equals(adapter)) {
            return new PgvectorAdapter(holder.getDataSource());
        }
        log.warn("Wenshi: unknown vectorstore adapter '{}'", adapter);
        return new PgvectorAdapter(holder.getDataSource());
    }
}