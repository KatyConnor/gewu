package com.gewu.application.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.domain.ai.ModelConfig;
import com.gewu.domain.session.Session;
import com.gewu.infrastructure.mapper.ModelConfigMapper;
import com.gewu.infrastructure.mapper.SessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 会话成本核算服务（T4.1）。
 * <p>执行完成后按模型单价将 token 用量累计到会话维度
 * （session.tokens_input/output/reasoning + cost），原子 UPDATE 消除并发覆盖。
 * 单价来自 model_config.price_per_1k_input/output（0 或未配置 = 不计费）。
 * 流式路径无 usage 时按字符数 /4 估算（结果偏差记录在日志）。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CostAccountingService {

    /** token 估算除数：经验值 1 token ≈ 4 字符 */
    private static final int CHARS_PER_TOKEN = 4;

    private final SessionMapper sessionMapper;
    private final ModelConfigMapper modelConfigMapper;

    /**
     * 记录一次执行的真实 usage（供应商返回）。
     */
    public void recordUsage(String sessionId, String modelId,
                            Integer promptTokens, Integer completionTokens, Integer reasoningTokens) {
        record(sessionId, modelId,
                promptTokens != null ? promptTokens : 0,
                completionTokens != null ? completionTokens : 0,
                reasoningTokens != null ? reasoningTokens : 0);
    }

    /**
     * 记录一次执行的估算 usage（流式路径供应商未返回 usage 时）：
     * 输入按用户消息字符估算，输出按回复内容字符估算。
     */
    public void recordEstimatedUsage(String sessionId, String modelId,
                                     String userMessage, String assistantContent) {
        int estimatedInput = estimateTokens(userMessage);
        int estimatedOutput = estimateTokens(assistantContent);
        log.debug("流式 usage 估算: sessionId={}, model={}, input~{}, output~{}",
                sessionId, modelId, estimatedInput, estimatedOutput);
        record(sessionId, modelId, estimatedInput, estimatedOutput, 0);
    }

    private void record(String sessionId, String modelId, int inputTokens,
                        int outputTokens, int reasoningTokens) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            BigDecimal cost = computeCost(modelId, inputTokens, outputTokens);
            sessionMapper.appendUsage(sessionId, inputTokens, outputTokens, reasoningTokens, cost);
            log.debug("会话用量累计: sessionId={}, in={}, out={}, reasoning={}, cost={}",
                    sessionId, inputTokens, outputTokens, reasoningTokens, cost);
        } catch (Exception e) {
            log.warn("会话成本核算失败（忽略）: sessionId={}, cause={}", sessionId, e.getMessage());
        }
    }

    /** 按模型单价计算成本：input/1000*priceIn + output/1000*priceOut */
    private BigDecimal computeCost(String modelId, int inputTokens, int outputTokens) {
        BigDecimal priceIn = BigDecimal.ZERO;
        BigDecimal priceOut = BigDecimal.ZERO;
        if (modelId != null && !modelId.isBlank()) {
            try {
                List<ModelConfig> configs = modelConfigMapper.selectList(
                        new LambdaQueryWrapper<ModelConfig>()
                                .eq(ModelConfig::getModelId, modelId)
                                .eq(ModelConfig::getStatus, 1)
                                .last("LIMIT 1"));
                if (!configs.isEmpty()) {
                    ModelConfig config = configs.get(0);
                    priceIn = config.getPricePer1kInput() != null ? config.getPricePer1kInput() : BigDecimal.ZERO;
                    priceOut = config.getPricePer1kOutput() != null ? config.getPricePer1kOutput() : BigDecimal.ZERO;
                }
            } catch (Exception e) {
                log.debug("模型单价查询失败（按 0 计费）: model={}, cause={}", modelId, e.getMessage());
            }
        }
        BigDecimal inputCost = BigDecimal.valueOf(inputTokens).multiply(priceIn)
                .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
        BigDecimal outputCost = BigDecimal.valueOf(outputTokens).multiply(priceOut)
                .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
        return inputCost.add(outputCost);
    }

    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.length() / CHARS_PER_TOKEN;
    }
}
