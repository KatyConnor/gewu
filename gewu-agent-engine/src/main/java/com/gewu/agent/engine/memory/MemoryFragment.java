package com.gewu.agent.engine.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 记忆片段 - 记忆存储的基本单元。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryFragment {

    /** 记忆 ID */
    private String id;
    /** 记忆域（角色隔离标识） */
    private String domain;
    /** 记忆类型：semantic / episodic / procedural / parametric / experience */
    private String type;
    /** 内容 */
    private String content;
    /** 向量（由使用方在存储时生成） */
    private float[] vector;
    /** 关联元数据 */
    private java.util.Map<String, Object> metadata;
    /** 相关度评分（检索时填充） */
    private double score;
}