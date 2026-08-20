package com.gewu.interfaceapi.controller;

import com.gewu.application.evaluation.EvaluationService;
import com.gewu.application.evaluation.LlmJudge;
import com.gewu.application.evaluation.SPCDegradationDetector;
import com.gewu.common.result.Result;
import com.gewu.domain.evaluation.EvaluationRecordEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 评测 API - LlmJudge 评估记录查询、手动评估与 SPC 劣化报告。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/evaluations")
@RequiredArgsConstructor
@Tag(name = "质量评测", description = "LlmJudge 评测与 SPC 劣化检测")
public class EvaluationController {

    private final EvaluationService evaluationService;

    @GetMapping
    @Operation(summary = "查询最近评测记录")
    public Result<List<EvaluationRecordEntity>> listRecent(@RequestParam(defaultValue = "20") int limit) {
        return Result.success(evaluationService.listRecent(limit));
    }

    @GetMapping("/degradation")
    @Operation(summary = "查询 SPC 劣化检测报告")
    public Result<SPCDegradationDetector.DegradationReport> degradation() {
        return Result.success(evaluationService.checkDegradation());
    }

    @PostMapping("/judge")
    @Operation(summary = "手动评估输出质量", description = "自定义验收标准，对指定输出执行 LLM-as-Judge 评估")
    public Result<LlmJudge.EvaluationResult> judge(@RequestBody JudgeRequest request) {
        return Result.success(evaluationService.evaluateManual(
                request.getExecutionId(), request.getScenario(),
                request.getAcceptanceCriteria(), request.getOutput()));
    }

    @Data
    public static class JudgeRequest {
        private String executionId;
        private String scenario;
        /** 验收标准（自然语言） */
        private String acceptanceCriteria;
        /** 待评估输出 */
        private String output;
    }
}
