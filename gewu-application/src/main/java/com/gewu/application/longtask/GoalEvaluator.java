package com.gewu.application.longtask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.llm.LlmClientRegistry;
import com.gewu.agent.engine.llm.model.LlmRequest;
import com.gewu.agent.engine.llm.model.LlmResponse;
import com.gewu.application.sandbox.SandboxClient;
import com.gewu.domain.longtask.AgentLongTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 目标达标双层评估器（docs/design/45 §6）：
 * <p>① 机械检查（确定性）：command（沙箱 exec，exit/输出断言）与 file_exists——客观证据，优先级最高；
 * <p>② LLM 判官（llm_judge）：以目标声明 + 机械证据 + 仓库产物清单为输入，输出 achieved/gaps——
 * 提示词强制"仅可引用机械检查输出与文件内容作为证据"，防目标投机。
 * <p>判官模型未配置时仅机械检查生效；单项检查异常按失败收敛（安全方向）。
 */
@Slf4j
@Component
public class GoalEvaluator {

    /** 判官单次评估输出截断上限 */
    private static final int EVIDENCE_CAP = 4000;

    private final SandboxClient sandboxClient;
    private final LlmClientRegistry llmClientRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GoalEvaluator(SandboxClient sandboxClient, LlmClientRegistry llmClientRegistry) {
        this.sandboxClient = sandboxClient;
        this.llmClientRegistry = llmClientRegistry;
    }

    
    public record GoalEvalResult(boolean achieved, List<Item> items, String summary) {
    }

    public record Item(String name, String type, boolean pass, String evidence) {
    }

    /** 对任务当前状态做一次达标评估（机械检查 + 判官） */
    public GoalEvalResult evaluate(AgentLongTask task, String sandboxId, String repoContext) {
        GoalCriteria criteria = GoalCriteria.parse(task.getCriteriaJson());
        List<Item> items = new ArrayList<>();
        for (GoalCriteria.Check check : criteria.getChecks()) {
            try {
                items.add(runCheck(task, sandboxId, repoContext, check));
            } catch (Exception e) {
                items.add(new Item(check.name(), check.type(), false,
                        "检查执行异常: " + e.getMessage()));
            }
        }
        boolean achieved = !items.isEmpty() && items.stream().allMatch(Item::pass);
        String summary = "检查 " + items.size() + " 项，通过 "
                + items.stream().filter(Item::pass).count() + " 项";
        return new GoalEvalResult(achieved, items, summary);
    }

    private Item runCheck(AgentLongTask task, String sandboxId, String repoContext, GoalCriteria.Check check) {
        return switch (check.type()) {
            case GoalCriteria.TYPE_COMMAND -> runCommandCheck(task, sandboxId, check, repoContext);
            case GoalCriteria.TYPE_FILE_EXISTS -> runFileCheck(task, sandboxId, check, repoContext);
            case GoalCriteria.TYPE_LLM_JUDGE -> runLlmJudge(task, sandboxId, check, repoContext);
            default -> new Item(check.name(), check.type(), false, "未知检查类型");
        };
    }

    /** 命令检查：沙箱内执行，exit=0 视为通过；expect 非空时额外要求输出包含该正则 */
    private Item runCommandCheck(AgentLongTask task, String sandboxId, GoalCriteria.Check check, String repoContext) {
        var resp = sandboxClient.execCommand(sandboxId, check.cmd(), 300);
        boolean pass = resp.getExitCode() != null && resp.getExitCode() == 0;
        String evidence = "exit=" + resp.getExitCode()
                + (resp.getStdout() != null && !resp.getStdout().isBlank()
                ? "，输出尾段: " + tail(resp.getStdout(), 300) : "");
        if (pass && check.expect() != null && !check.expect().isBlank()) {
            pass = (resp.getStdout() + resp.getStderr()).matches("(?s).*" + check.expect() + ".*");
            evidence += "，期望匹配: " + check.expect() + (pass ? " ✓" : " ✗");
        }
        return new Item(check.name(), check.type(), pass, evidence);
    }

    /** 产物存在性检查（卷内相对路径） */
    private Item runFileCheck(AgentLongTask task, String sandboxId, GoalCriteria.Check check, String repoContext) {
        String path = sanitizeRel(check.path());
        if (path.isBlank()) {
            return new Item(check.name(), check.type(), false, "路径为空");
        }
        var resp = sandboxClient.execCommand(sandboxId,
                "test -e " + shellQuote("/workspace/" + path) + " && echo EXISTS", 10);
        boolean pass = resp.getExitCode() != null && resp.getExitCode() == 0
                && resp.getStdout() != null && resp.getStdout().contains("EXISTS");
        return new Item(check.name(), check.type(), pass,
                pass ? "产物已存在: " + path : "产物不存在: " + path);
    }

    /** LLM 判官：以目标声明 + 机械证据 + 仓库上下文为输入，输出结构化达标判定 */
    private Item runLlmJudge(AgentLongTask task, String sandboxId, GoalCriteria.Check check, String repoContext) {
        if (task.getProviderCode() == null || task.getProviderCode().isBlank()
                || task.getModel() == null || task.getModel().isBlank()) {
            return new Item(check.name(), "llm_judge", false, "未配置判官模型（provider/model），无法执行综合评估");
        }
        String judgePrompt = "你是目标验收判官。仅依据下方【机械检查证据】与【仓库产物上下文】判定目标是否达成，"
                + "禁止采信任何未在证据中出现的内容。\n"
                + "【目标声明】" + (task.getGoalText() == null ? "" : task.getGoalText()) + "\n"
                + "【判官检查项】" + check.prompt() + "\n"
                + "【仓库产物上下文】\n" + (repoContext == null ? "（无）" : truncate(repoContext, EVIDENCE_CAP))
                + "\n请仅输出 JSON：{\"achieved\": true|false, \"summary\": \"判定摘要\", \"gaps\": [\"未达成点\"]}";
        LlmRequest request = LlmRequest.builder()
                .model(task.getModel())
                .messages(List.of(
                        com.gewu.agent.engine.llm.model.Message.builder().role("system")
                                .content("你是严格的目标验收判官，只输出 JSON。").build(),
                        com.gewu.agent.engine.llm.model.Message.builder().role("user")
                                .content(judgePrompt).build()))
                .stream(false)
                .maxTokens(1024)
                .build();
        var client = llmClientRegistry.getClient(task.getProviderCode());
        if (client == null) {
            return new Item(check.name(), "llm_judge", false, "判官供应商不可用: " + task.getProviderCode());
        }
        LlmResponse resp = client.chat(request);
        String out = resp.getContent() != null ? resp.getContent() : "";
        boolean achieved = extractJsonField(out, "achieved").map(Boolean::parseBoolean).orElse(false);
        String summary = extractJsonField(out, "summary").orElse("（判官未给出摘要）");
        return new Item(check.name(), "llm_judge", achieved,
                summary + (achieved ? "" : "，差距: " + truncate(extractJsonField(out, "gaps").orElse(""), 200)));
    }

    private static String truncate(String s, int n) {
        return s != null && s.length() > n ? s.substring(0, n) + "…" : s;
    }

    /** 从判官输出中宽松提取 JSON 字段（容忍 markdown 代码块包裹） */
    private java.util.Optional<String> extractJsonField(String out, String field) {
        try {
            String s = out;
            int b = s.indexOf('{');
            int e = s.lastIndexOf('}');
            if (b >= 0 && e > b) {
                s = s.substring(b, e + 1);
            }
            JsonNode node = objectMapper.readTree(s);
            return node.hasNonNull(field) ? java.util.Optional.of(node.get(field).asText()) : java.util.Optional.empty();
        } catch (Exception ex) {
            return java.util.Optional.empty();
        }
    }

    private static String tail(String s, int n) {
        String t = s == null ? "" : s.trim();
        return t.length() > n ? "…" + t.substring(t.length() - n) : t;
    }

    /** 卷内相对路径净化：剥前导斜杠、拒绝 .. 越界（L3-SEC-006） */
    private static String sanitizeRel(String path) {
        if (path == null) {
            return "";
        }
        String p = path.trim();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.contains("..") || p.isBlank()) {
            return "";
        }
        return p;
    }

    private static String shellQuote(String s) {
        return "'" + (s == null ? "" : s.replace("'", "")) + "'";
    }
}
