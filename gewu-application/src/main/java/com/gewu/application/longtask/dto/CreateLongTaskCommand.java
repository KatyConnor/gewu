package com.gewu.application.longtask.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 创建持久化目标驱动长任务请求体（docs/design/45）。 */
@Data
public class CreateLongTaskCommand {

    @NotBlank(message = "会话不能为空")
    private String sessionId;

    @NotBlank(message = "任务标题不能为空")
    private String title;

    @NotBlank(message = "目标声明不能为空")
    private String goalText;

    /** 验收标准 JSON：{"statement":"...","checks":[{"type":"command","cmd":"mvn -q test"},{"type":"file_exists","path":"..."},{"type":"llm_judge","prompt":"..."}],"review_required":true} */
    @NotBlank(message = "验收标准不能为空")
    private String criteriaJson;

    /** 执行天数上限（默认 7） */
    private Integer maxDays;

    /** 日 token 上限（0=不限） */
    private Long dailyTokenCap;

    /** 段执行智能体（空=继承会话智能体） */
    private String agentId;

    /** 目标判官 LLM 供应商（空=跳过 llm_judge 检查） */
    private String providerCode;

    /** 目标判官模型 */
    private String model;
}
