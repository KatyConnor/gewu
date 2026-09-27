package com.veloflow.engine.commons;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Veloflow 引擎内部 JSON Mapper。
 * <p>产品化约束（52 号 §3.2）：引擎不注入宿主 ObjectMapper Bean——宿主可能存在
 * 多个/特化配置的 Mapper（如平台 llmObjectMapper 使 Jackson 默认 Bean 消失），
 * 按类型/名称注入都会造成环境耦合。引擎自建并对未知字段宽松（平移平台默认行为）。
 */
public final class VeloflowJson {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private VeloflowJson() {
    }
}
