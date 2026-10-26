# 离线/在线/同步三模式部署手册（references/offline-deployment.md）

> 对应 UCSS v1.0 第 13 章。扫描开始前按 §1 判定网络模式，之后全程遵守对应纪律。操作细节在本文件，标准要求在主规范第 13 章。

## §1 网络模式判定（每次扫描 Step 0 执行）

```
1) 环境变量 UCSS_NETWORK_MODE 有值（OFFLINE/ONLINE）→ 直接采用
2) 否则探测缓存：UCSS_CACHE_DIR（默认 ~/.ucss/cache/）存在且 manifest.json 有效
   → SYNC-consumed 模式（有缓存，禁止外联，用缓存）
3) 否则快速探测外网（仅 DNS + 1 次 HEAD，超时 2s）
   → 通：ONLINE；不通/超时：OFFLINE
4) 均不确定 → 默认 OFFLINE（安全默认：不确定时绝不外联）
```

判定结果写入报告头 `mode:` 字段。

## §2 各模式下的命令变体

### OFFLINE / SYNC-consumed（禁止外联）

```bash
# 兜底盘：UCSS-PyScan（V1.2·纯标准库·OFFLINE 永在——无任何外联动作）
python3 .standards/ucss/scripts/pyscan.py scan . --sarif pyscan.sarif --net offline
python3 .standards/ucss/scripts/pyscan.py sca requirements.txt --net offline
# （SCA 离线查询需先在在线窗口跑过 ucss-sync.py 生成快照 ~/.ucss/cache/osv-pypi.json；
#   无快照时输出「未检查」而非放弃——结论表述「截至 {快照时间}」）

# Semgrep：用本地缓存规则，禁 --config auto
semgrep --config ~/.ucss/cache/semgrep-rules/ --sarif -o semgrep.sarif .
# 或用项目内自带的规则目录
semgrep --config .ucss-rules/ --sarif -o semgrep.sarif .

# Trivy：锁定缓存库，禁自动更新
trivy fs --cache-dir ~/.ucss/cache/trivy-db --skip-db-update --scanners vuln .

# osv-scanner：本地库模式
osv-scanner --local ~/.ucss/cache/advisories/ -r .

# Python 工具离线安装（如有 wheels 缓存）
pip install --no-index --find-links ~/.ucss/cache/wheels/ bandit
pip download bandit -d ~/.ucss/cache/wheels/   # 仅同步机执行
```

### ONLINE（白名单外联）

```bash
semgrep --config auto --metrics=off --sarif -o semgrep.sarif .
trivy fs --scanners vuln .
npm audit --json || true
```

### 工具完全缺失时的降级（所有模式适用）

无任何本地工具 → 跳过 §2 工具步骤，直接按 SKILL.md Step2~4 用 `checklists.md` + `language-patterns.md` 做纯规则审查。**不得因缺工具放弃扫描**，并在报告 limitations 标注：`未执行工具扫描（原因），仅完成规则库静态审查`。

## §3 缓存健康检查（SYNC 消费端每日）

```bash
python3 <skill>/scripts/sync_resources.py status
```

过期阈值（超限须在报告 limitations 显著提示）：
| 资源 | 黄线 | 红线 |
|---|---|---|
| 漏洞库（trivy-db/advisories） | 7 天 | 30 天 |
| Semgrep 规则 | 30 天 | 90 天 |
| 工具包 | 90 天 | 180 天 |

红线状态下 SCA 结论表述必须为：`截至 {库版本} 未发现已知漏洞`，禁止表述为"安全"。

## §4 同步机操作（SYNC 模式，可出网机器）

```bash
# 1) 全量同步（单项失败不中断，结果写入 manifest.json）
python3 scripts/sync_resources.py sync
# 2) 查看同步状态
python3 scripts/sync_resources.py status
# 3) 打包离线资源包（内网摆渡用）
python3 scripts/sync_resources.py pack
# 4) 内网消费端导入（自动 sha256 校验）
python3 scripts/sync_resources.py import ucss-offline-pack-YYYYMMDD.zip
```

白名单域（同步机出网策略）：`github.com`、`semgrep.dev`、`api.osv.dev`、`pypi.org/files.pythonhosted.org`、`registry.npmjs.org`、`ghcr.io`、`mirror.gcr.io`。

## §5 企业内网部署拓扑

```
[互联网] --白名单--> [同步机 SYNC] --pack zip--> [内网镜像仓/摆渡盘]
                                    ├─> 扫描节点 A（ZCode + Skill + 缓存）
                                    ├─> 扫描节点 B
                                    └─> CI Runner（ucss_gate.py + 缓存库）
商用工具（奇安信代码卫士等）：内网部署版，规则库走官方离线升级包，与同步机制并行。
```

## §6 安全纪律

1. 导入资源包必须 sha256 校验通过（import 自动执行）；
2. 在线拉取的 Semgrep 规则首次使用前人工抽查规则内容（防恶意规则注入执行面）；
3. 禁止上传源码到任何在线服务；ONLINE 模式外联仅限 §4 白名单域并留痕日志；
4. 缓存目录权限：仅扫描账号可写，防缓存投毒。

## §7 缓存目录规范

```
~/.ucss/cache/（UCSS_CACHE_DIR 可重定向）
├── semgrep-rules/   trivy-db/   advisories/
├── wheels/   npm-tarballs/
├── packs/           # 导出的离线资源包
└── manifest.json    # 版本+时间+sha256，无 manifest 的缓存视为不可信
```

---
*UCSS v1.0 · references/offline-deployment.md*
