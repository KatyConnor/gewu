# 工具链与 CI 集成（references/toolchain.md）

> 智能体环境能执行 shell 时优先直接运行工具获取机器结果，再按 UCSS 规则复核；无工具环境时按 SKILL.md 工作流做人工级静态审查。

## §1 三级降级链工具策略（V1.2：+兜底盘）

| 轨道 | 时机 | 工具 | 说明 |
|---|---|---|---|
| 深度轨 | 合并前全量 / 发布前 | 奇安信代码卫士 / Fortify / Checkmarx / CodeQL / SonarQube | 跨文件污点分析 |
| 敏捷轨 | IDE / 提交 / 智能体会话内 | Semgrep / bandit / gosec / eslint-security / gitleaks / trivy | 秒级反馈 |
| **兜底盘（V1.2）** | 任何环境（内网/工具全无） | **UCSS-PyScan（本包自带）** | 64 条规则（56 py AST+8 js 正则层）+污点链+SCA 双模·纯标准库·装了 Python 就有 |

> 兜底盘纪律：环境探察到敏捷/深度轨工具缺位时**必须**落 L3（scripts/pyscan.py），降级事实写入报告
> limitations——「不得放弃扫描」条款的机器执行位。引擎与模型分工：引擎管确定性发现（C1/C2/C3+
> SARIF+退出码），模型管研判（误报复核/修复建议/D4 逻辑审计——能力正交互补）。

## §2 快速执行命令（敏捷轨）

> ⚠️ 网络模式先行：执行任何命令前先按 SKILL.md Step 0 判定模式；OFFLINE/SYNC-consumed 下使用 references/offline-deployment.md §2 的离线命令变体（本地规则/锁定缓存库/禁自动更新）。


```bash
# 兜底盘：UCSS-PyScan（V1.2·纯标准库·任何环境可跑；--net 不传时脚本层自动探测）
python3 .standards/ucss/scripts/pyscan.py scan src/ --sarif pyscan.sarif --net offline
python3 .standards/ucss/scripts/pyscan.py scan src/ --sarif pyscan.sarif          # auto 探测
python3 .standards/ucss/scripts/pyscan.py sca requirements.txt --net offline      # 离线快照查 CVE
python3 .standards/ucss/scripts/pyscan.py selftest                               # 引擎回归自检（棘轮）
python3 .standards/ucss/scripts/ucss-sync.py                                     # OSV 快照同步（在线执行·产离线资产）
# 门禁聚合（与 semgrep/bandit SARIF 同格式去重互证）
python3 .standards/ucss/scripts/ucss_gate.py --fail-on critical,high pyscan.sarif

# Semgrep（推荐统一入口，结果转 SARIF）
semgrep --config auto --sarif -o semgrep.sarif .

# Python
bandit -r src/ -f sarif -o bandit.sarif || true
ruff check src/ --output-format json

# Node/JS/TS
npx eslint . --format @microsoft/eslint-formatter-sarif -o eslint.sarif

# Go
gosec -fmt sarif -out gosec.sarif ./...
golangci-lint run

# Java
mvn com.github.spotbugs:spotbugs-maven-plugin:check   # 配合 FindSecBugs
# 或 gradle spotbugsMain

# C/C++
cppcheck --enable=all --inline-suppr --xml . 2> cppcheck.xml
flawfinder --sarif . > flawfinder.sarif

# C#
dotnet build /p:RunAnalyzers=true   # Roslyn analyzers

# PHP / Ruby
vendor/bin/psalm --taint-analysis
brakeman -q -o brakeman.json

# Rust
cargo clippy -- -D warnings && cargo audit

# 密钥扫描
gitleaks detect --report-format sarif -o gitleaks.sarif --redact

# 依赖（SCA）
trivy fs --scanners vuln,secret,misconfig --format sarif -o trivy.sarif .
osv-scanner --format sarif -o osv.sarif ./  || true

# SBOM 生成
syft . -o cyclonedx-json > sbom.cdx.json
```

> 深度轨（企业环境）：奇安信代码卫士按其控制台任务接入（支持 Java/C/C++/C#/OC/Swift/JS/PHP/Python/Cobol/Go 等 20+ 语言、1300+ 缺陷规则）；SonarQube 用 `sonar-scanner` + Quality Gate API 取结果。

## §3 结果归一化（SARIF 2.1.0 字段映射）

```
ruleId          ← UCSS 编号 + 原生规则 ID 双写（如 "SEC-INJ-01 | py/sql-injection"）
level           ← error=Critical/High；warning=Medium；note=Low/Info
properties.cwe  ← 必填
properties.owasp← 如 "A03:2021"
properties.cvss ← {score, vector}
properties.confidence ← C1/C2/C3
fingerprints[".standards/ucss/v1"] ← sha256(规则+文件+归一化行内容)
```

多工具合并去重：以 fingerprint 为键；同位置同 CWE 保留等级最高者。

## §4 CI 门禁片段

### GitHub Actions

```yaml
name: ucss-gate
on: [pull_request]
jobs:
  scan:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }        # gitleaks 需要历史
      - name: Secrets scan
        uses: gitleaks/gitleaks-action@v2
        env: { GITHUB_TOKEN: "${{ secrets.GITHUB_TOKEN }}" }
      - name: Semgrep (UCSS agile track)
        run: |
          pip install semgrep
          semgrep --config auto --sarif -o semgrep.sarif .
      - name: SCA
        run: |
          docker run --rm -v "$PWD:/src" aquasec/trivy fs \
            --scanners vuln,secret,misconfig --exit-code 1 --format sarif -o trivy.sarif /src || true
      - name: UCSS gate
        run: python .ci/ucss_gate.py --min-severity HIGH --fail-on "critical,high" *.sarif
```

### GitLab CI

```yaml
ucss-scan:
  stage: test
  image: returntocorp/semgrep
  script:
    - semgrep --config auto --sarif -o semgrep.sarif .
  artifacts: { paths: [semgrep.sarif] }
ucss-gate:
  stage: test
  script: python3 .ci/ucss_gate.py --fail-on "critical,high" semgrep.sarif
```

### 本地 pre-commit（.pre-commit-config.yaml）

```yaml
repos:
  - repo: https://github.com/gitleaks/gitleaks
    rev: v8.18.0
    hooks: [{id: gitleaks}]
  - repo: https://github.com/PyCQA/bandit
    rev: 1.7.9
    hooks: [{id: bandit, args: ["-q", "-c", "bandit.yaml"]}]
```

## §5 工具 ↔ UCSS 规则映射速查

| UCSS 规则 | bandit | Semgrep | gosec | SpotBugs/FB | ESLint security |
|---|---|---|---|---|---|
| SEC-INJ-01 SQL 注入 | B608 | sql-injection 系列 | G201/G202 | SQL_INJECTION(FindSecBugs) | — |
| SEC-INJ-02 命令注入 | B602~B609 | subprocess-shell 系列 | G204 | COMMAND_INJECTION | — |
| SEC-AUTH-07 弱随机 | B311 | weak-random | G404 | PREDICTABLE_RANDOM | — |
| SEC-CRY-01 弱算法 | B303/B324 | crypto 系列 | G401/G501 | WEAK_* | — |
| SEC-AUTH-01 硬编码凭证 | B105/B106/B107 | generic-secrets | G101 | HARD_CODE_PASSWORD | no-unsanitized 辅助 |
| SEC-INJ-05 XXE | — | xml 系列 | — | XXE_SAXPARSER 等 | — |
| SEC-INJ-03 XSS | B701(jinja) | eslint 规则 | — | XSS_SERVLET | no-unsanitized/property |

> 完整映射以 checklists.md §8 为准；工具报告字段按 §3 归一化为 UCSS 编号。

## §6 智能体会话内的轻量执行模板

```bash
# 1) 识别栈
ls package.json pom.xml requirements.txt go.mod Cargo.toml Gemfile *.csproj 2>/dev/null
# 2) 有工具则跑（择一示例）
(command -v semgrep && semgrep --config auto --json . ) || echo "no semgrep, manual audit"
# 3) 无工具：按 SKILL.md Step2~4 + language-patterns.md 逐文件审查（优先：入口路由→控制器→数据访问层）
```

---
*UCSS v1.0 · references/toolchain.md*
