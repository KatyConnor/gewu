package com.gewu.infrastructure.wenshi.adapter;

import java.util.List;

/**
 * 文本嵌入适配器接口 — 将文本编码为固定维度的浮点向量。
 * <p>
 * 支持多种实现：BGE-small（本地 ONNX 推理）、LLM 原生 embedding API。
 * 实现类必须保证线程安全，向量输出应经过 L2 归一化处理。
 *
 * @since 1.0.0
 */
public interface EmbeddingAdapter {

    /**
     * 将单条文本编码为嵌入向量。
     *
     * @param text 待编码文本，不可为 null
     * @return 嵌入向量，长度等于 {@link #getDimension()}，已 L2 归一化
     */
    float[] embed(String text);

    /**
     * 批量编码文本列表为嵌入向量数组。
     * <p>
     * 默认逐条调用 {@link #embed(String)}，实现类可覆写为批量推理以提升性能。
     *
     * @param texts 待编码文本列表，不可为 null
     * @return 嵌入向量二维数组，与输入列表一一对应
     */
    float[][] embedBatch(List<String> texts);

    /**
     * 返回嵌入向量的维度大小。
     *
     * @return 向量维度，正整数
     */
    int getDimension();

    /**
     * 返回嵌入提供方标识。
     * <p>
     * 用于日志区分和监控统计，如 "bge-small"、"llm-native"。
     *
     * @return 提供方名称，不可为 null
     */
    String getProvider();
}
