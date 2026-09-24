package com.gewu.application.session.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 会话运行实时过程 DTO（重进会话可见执行中任务的时间线）：
 * items 为与消息 metadata.process 完全同构的 compact 条目数组（前端解析器共用），
 * 由执行中轮次的 processSummary 活引用实时读取；无活动 run 时 items 为空。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunProcessDTO {

    /** 实时过程条目（k=thinking/tool/content/ask/subagent，与 metadata.process 同构） */
    private List<Map<String, Object>> items;

    /** 本轮开始时间戳（毫秒） */
    private long startedAt;
}
