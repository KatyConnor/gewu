package com.gewu.application.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.common.context.UserContext;
import com.gewu.common.ulid.Ulid;
import com.gewu.domain.ai.ModelConfig;
import com.gewu.domain.usage.UsageLedger;
import com.gewu.infrastructure.mapper.ModelConfigMapper;
import com.gewu.infrastructure.mapper.SessionMapper;
import com.gewu.infrastructure.mapper.UsageLedgerMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 会话成本核算服务（T4.1）。
 * <p>执行完成后按模型单价将 token 用量累计到会话维度
 * （session.tokens_input/output/reasoning + cost），原子 UPDATE 消除并发覆盖；
 * 同时写一条 {@link UsageLedger} 用量流水（用户套餐配额窗口聚合与用量统计的数据源）。
 * <p>计价口径：cost = input/unit×priceIn + output/unit×priceOut，unit 取
 * model_config.price_unit_tokens（计价基准，默认 1000，可设 1000000）；
 * 单价来自 model_config.price_per1k_input/output（0 或未配置 = 不计费）。
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
    /** 计价基准缺省值（price_unit_tokens 未配置时） */
    private static final int DEFAULT_PRICE_UNIT_TOKENS = 1000;

    private final SessionMapper sessionMapper;
    private final ModelConfigMapper modelConfigMapper;
    private final UsageLedgerMapper usageLedgerMapper;

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
            appendLedger(sessionId, modelId, inputTokens, outputTokens, reasoningTokens, cost);
            log.debug("会话用量累计: sessionId={}, in={}, out={}, reasoning={}, cost={}",
                    sessionId, inputTokens, outputTokens, reasoningTokens, cost);
        } catch (Exception e) {
            log.warn("会话成本核算失败（忽略）: sessionId={}, cause={}", sessionId, e.getMessage());
        }
    }

    /** 写用量流水（用户套餐配额窗口聚合与用量统计的数据源）；userId 取当前登录上下文 */
    private void appendLedger(String sessionId, String modelId, int inputTokens,
                              int outputTokens, int reasoningTokens, BigDecimal cost) {
        try {
            String userId = UserContext.currentUserId();
            if (userId == null || userId.isBlank()) {
                return;
            }
            String provider = null;
            List<ModelConfig> configs = modelConfigMapper.selectList(
                    new LambdaQueryWrapper<ModelConfig>()
                            .eq(ModelConfig::getModelId, modelId)
                            .eq(ModelConfig::getStatus, 1)
                            .last("LIMIT 1"));
            if (!configs.isEmpty()) {
                provider = resolveProviderCode(configs.get(0));
            }
            UsageLedger ledger = new UsageLedger();
            ledger.setId(Ulid.next());
            ledger.setUserId(userId);
            ledger.setSessionId(sessionId);
            ledger.setModelId(modelId);
            ledger.setProvider(provider);
            ledger.setInputTokens(inputTokens);
            ledger.setOutputTokens(outputTokens);
            ledger.setReasoningTokens(reasoningTokens);
            ledger.setTotalTokens(inputTokens + outputTokens + reasoningTokens);
            ledger.setCost(cost);
            usageLedgerMapper.insert(ledger);
        } catch (Exception e) {
            // 流水记账失败不影响会话累计（配额与统计为辅助能力）
            log.warn("用量流水记账失败（忽略）: sessionId={}, cause={}", sessionId, e.getMessage());
        }
    }

    /** 供应商标识从 model_params 之外的实体字段不可得时留空（由调用方按需补充） */
    private String resolveProviderCode(ModelConfig config) {
        // ModelConfig 实体仅含 providerId；code 需联表，此处返回 id 供统计维度兜底
        return config.getProviderId();
    }

    /** 按模型单价与计价单位计算成本：input/unit*priceIn + output/unit*priceOut */
    private BigDecimal computeCost(String modelId, int inputTokens, int outputTokens) {
        BigDecimal priceIn = BigDecimal.ZERO;
        BigDecimal priceOut = BigDecimal.ZERO;
        int unit = DEFAULT_PRICE_UNIT_TOKENS;
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
                    if (config.getPriceUnitTokens() != null && config.getPriceUnitTokens() > 0) {
                        unit = config.getPriceUnitTokens();
                    }
                }
            } catch (Exception e) {
                log.debug("模型单价查询失败（按 0 计费）: model={}, cause={}", modelId, e.getMessage());
            }
        }
        BigDecimal inputCost = BigDecimal.valueOf(inputTokens).multiply(priceIn)
                .divide(BigDecimal.valueOf(unit), 6, RoundingMode.HALF_UP);
        BigDecimal outputCost = BigDecimal.valueOf(outputTokens).multiply(priceOut)
                .divide(BigDecimal.valueOf(unit), 6, RoundingMode.HALF_UP);
        return inputCost.add(outputCost);
    }

    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.length() / CHARS_PER_TOKEN;
    }
}
