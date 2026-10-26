# 扫描报告模板（references/report-template.md）

> 输出报告前加载本文件。智能体在会话中输出 Markdown 版报告；CI 环境输出 SARIF + JSON 摘要。

## §1 Markdown 报告结构（会话内输出）

```markdown
# 代码扫描报告（UCSS v1.0）

**项目**：{repo/项目名}　**扫描ID**：{uuid}　**Profile**：full|incremental
**提交**：{commit_sha / branch}　**时间**：{YYYY-MM-DD HH:mm TZ}
**语言栈**：{languages}　**场景**：{web/api/mobile/...}
**执行工具**：{工具列表或 manual-audit}
**网络模式**：{OFFLINE | ONLINE | SYNC-consumed}　**漏洞库版本**：{cache 日期，落后 {n} 天，仅 OFFLINE/SYNC-consumed 必填}

## 一、总览

| 等级 | Critical | High | Medium | Low | Info |
|---|---|---|---|---|---|
| 数量 | n | n | n | n | n |

分域统计：D1 安全 {n} · D2 缺陷 {n} · D3 规范 {n} · D4 逻辑 {n} · D5 供应链 {n}
密度（/KLOC）：安全 {x} · 可靠 {x} · 性能 {x} · 可维护 {x}

## 二、门禁判定

**结论：PASS / FAIL / FAIL_WITH_WAIVER**
- 阻断项：[列出触发门禁的 finding 编号]
- 豁免项：[工单号+有效期]

## 三、问题明细（按等级降序）

[Critical] SEC-INJ-01 SQL注入 (CWE-89, OWASP A03:2021, C1, CVSS 9.8)
  位置：{file}:{line}
  证据：{污点链：source@L12 → prop@L30 → sink@L42}
  危害：{一句话}
  修复：
    反例：{代码}
    正例：{代码}
  参考：{ASVS/Cheat Sheet 条目}

（逐条列出；D4 问题附 reasoning 推理链）

## 四、修复优先级 Top 5

1. {编号+一句话动作}（预计 {h/d}）

## 五、扫描限制与自评

- 未执行：{...}　- C3 待复核：{N 项}　- 未覆盖：{...}
```

## §2 机器可读摘要（JSON）

```json
{
  "report_id": "uuid",
  "standard": "UCSS-1.0",
  "project": "repo",
  "commit": "sha",
  "scan_profile": "full|incremental",
  "languages": [],
  "network_mode": "OFFLINE|ONLINE|SYNC-consumed",
  "cache_versions": {"trivy-db": "YYYY-MM-DD", "semgrep-rules": "YYYY-MM-DD", "advisories": "YYYY-MM-DD"},
  "tools": [{"name": "", "version": "", "track": "agile|deep"}],
  "summary": {
    "by_severity": {"critical": 0, "high": 0, "medium": 0, "low": 0, "info": 0},
    "by_domain": {"D1": 0, "D2": 0, "D3": 0, "D4": 0, "D5": 0},
    "density_kloc": {"security": 0, "reliability": 0, "performance": 0, "maintainability": 0}
  },
  "gate_result": "PASS|FAIL|FAIL_WITH_WAIVER",
  "findings": [
    {
      "id": "fingerprint",
      "rule": "SEC-INJ-01",
      "cwe": "CWE-89",
      "owasp": "A03:2021",
      "severity": "Critical",
      "confidence": "C1",
      "cvss": {"score": 9.8},
      "location": {"file": "", "line": 42, "snippet": ""},
      "taint_trace": ["source@12", "propagator@30", "sink@42"],
      "reasoning": "",
      "fix_advice": {"bad": "", "good": ""},
      "references": ["ASVS V5.3.4"]
    }
  ],
  "limitations": ["..."]
}
```

## §3 finding 写作规范

1. **标题**：`[等级] 编号 名称 (CWE-x, OWASP Axx:2021, 置信度[, CVSS])`；
2. **证据**：D1/D2 必须有污点链或行号证据；D4 必须有 `证据 → 推导 → 结论` 三段式；
3. **修复**：正/反代码对照；反例可直接引用现场代码，正例给出等价安全实现；
4. **打码**：涉及密钥/凭证一律 `sk-****abcd` 形式，不输出完整值；
5. **语气**：客观、可执行；不使用恐吓性措辞；每条独立可修复。

## §4 增量扫描（incremental）报告要求

- 标注范围：`diff base..head` 涉及文件清单；
- 区分「新增问题」与「存量问题（新路径覆盖）」；
- 门禁仅对新增问题阻断；存量问题列入趋势跟踪。

---
*UCSS v1.0 · references/report-template.md*
