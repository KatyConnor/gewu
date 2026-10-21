package com.gewu.application.longtask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * 目标验收标准（docs/design/45 §6）：机械检查优先 + LLM 判官补充。
 * <p>从 criteria JSON 解析；解析失败/字段缺失按安全默认收敛（statement 缺省、无 checks）。
 */
@Value
@Builder
public class GoalCriteria {

    public static final String TYPE_COMMAND = "command";
    public static final String TYPE_FILE_EXISTS = "file_exists";
    public static final String TYPE_LLM_JUDGE = "llm_judge";

    /** 目标声明（判官与种子提示使用） */
    String statement;

    /** 验收检查清单 */
    List<Check> checks;

    /** 达标后是否必须人工审核 */
    boolean reviewRequired;

    /** 每 K 段强制一次里程碑审核 */
    int milestoneEverySegments;

    public record Check(String type, String name, String cmd, String path, String expect, String prompt) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static GoalCriteria parse(String json) {
        String statement = null;
        boolean reviewRequired = true;
        int milestone = 6;
        List<Check> checks = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(json == null ? "{}" : json);
            if (root.hasNonNull("statement") && !root.get("statement").asText().isBlank()) {
                statement = root.get("statement").asText();
            }
            reviewRequired = !root.has("review_required") || root.get("review_required").asBoolean(true);
            if (root.hasNonNull("milestone_every_segments")) {
                milestone = Math.max(1, root.get("milestone_every_segments").asInt(6));
            }
            JsonNode arr = root.path("checks");
            if (arr.isArray()) {
                for (JsonNode c : arr) {
                    String type = c.path("type").asText("");
                    if (TYPE_COMMAND.equals(type)) {
                        if (c.hasNonNull("cmd") && !c.get("cmd").asText().isBlank()) {
                            checks.add(new Check(type, c.path("name").asText("命令检查"),
                                    c.get("cmd").asText(), null, c.path("expect").asText(null), null));
                        }
                    } else if (TYPE_FILE_EXISTS.equals(type)) {
                        if (c.hasNonNull("path") && !c.get("path").asText().isBlank()) {
                            checks.add(new Check(type, c.path("name").asText("产物检查"),
                                    null, c.get("path").asText(), null, null));
                        }
                    } else if (TYPE_LLM_JUDGE.equals(type) && c.hasNonNull("prompt")) {
                        checks.add(new Check(type, c.path("name").asText("综合评估"),
                                null, null, null, c.get("prompt").asText()));
                    }
                }
            }
        } catch (Exception e) {
            // 非法 criteria 按空标准收敛（仅 statement 生效），不阻塞任务创建
        }
        return new GoalCriteria(statement, List.copyOf(checks), reviewRequired, milestone);
    }
}
