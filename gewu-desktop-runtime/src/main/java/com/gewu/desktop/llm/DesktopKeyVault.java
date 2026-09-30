package com.gewu.desktop.llm;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 桌面模型 Key 保管（运行时内存态）。
 * <p>来源优先级：Electron 主进程 stdin 引导注入（生产）＞ gewu.desktop.model-keys（开发态 yml）。
 * Key 仅驻内存，不落盘不入库（docs/design/42 §11.1：密钥零明文落盘）。
 *
 * @since 1.0.0
 */
@Component
public class DesktopKeyVault {

    private final Map<String, String> keys = new ConcurrentHashMap<>();

    @jakarta.annotation.PostConstruct
    void loadFromEnv() {
        // 环境引导（EngineSupervisor spawn 注入 GEWU_MODEL_KEYS=JSON）；stdin 注入为 Phase 1 加固项
        String env = System.getenv("GEWU_MODEL_KEYS");
        if (env != null && !env.isBlank()) {
            try {
                com.fasterxml.jackson.databind.JsonNode node =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(env);
                node.fields().forEachRemaining(e -> keys.put(e.getKey(), e.getValue().asText()));
            } catch (Exception e) {
                // 非法 JSON：忽略环境引导（渲染层可经 /engine/providers 重新注入）
            }
        }
    }

    /** 引导注入（Electron 主进程 / yml 初始化） */
    public void loadAll(Map<String, String> modelKeys) {
        if (modelKeys != null) {
            keys.putAll(modelKeys);
        }
    }

    /** 运行期更新（设置页修改 Key） */
    public void put(String providerCode, String apiKey) {
        keys.put(providerCode, apiKey);
    }

    /** 取明文 Key；未配置返回 null（调用方回退空 Key，供本地 stub/代理场景） */
    public String get(String providerCode) {
        return keys.get(providerCode);
    }

    /** 移除（供应商删除时同步清除内存 Key） */
    public void remove(String providerCode) {
        keys.remove(providerCode);
    }

    public boolean has(String providerCode) {
        return keys.containsKey(providerCode);
    }
}
