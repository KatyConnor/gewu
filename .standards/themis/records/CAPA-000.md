> V1.9.28 口径归位：本单自 REV-000 演练内嵌段拆出为独立 CAPA 文件（四单契约归位·REV-000 原段保留作历史留痕——度量口径分母分子一致化见 rev_check.py V1.9.28 修正）。

# CAPA-000 整改与落地追踪

```yaml
id: CAPA-000
finding_refs: [F-01, F-02]
created_at: 2026-10-15T15:25:00+08:00
actions:
  - {action: META 判型注记补「单 Agent 独立性等价机制=角色分时+可复核证据对照+裁决记录公开」，owner: 鲁班, due: 批2（V1.9.26）, verify_method: spec-themis.yaml 注记在位}
  - {action: THEMIS §六 增「留痕类发现→AETC 稽核复核（trace 完整性/真实性）」第四路由, owner: 鲁班, due: 批2（V1.9.26）, verify_method: §六 字样在位}
recheck:
  rechecked_at: 2026-10-15T16:10:00+08:00（V1.9.26 批2 复审）
  result: 通过
  recheck_evidence: |
    CAPA-01：spec-themis.yaml 已含 independence_equivalence 三件套
    （角色分时/证据对照/裁决公开）+capa_01_cleared_at——verify_method 满足 ✓
    CAPA-02：THEMIS.md §六 已含「留痕类发现→转 AETC 稽核复核…红线事件走 §八流程」
    +单据命名约定（RPT-*.md）——verify_method 满足 ✓
    附加：批2 欠账全清（rev_check.py 四场景自测/交接物 themis-rpt 行+handoff 6 场景/
    词族语境分界入 TRIGGER §十六/REV-000 闭环本身）——RPT-000 两 conditions 全清偿。
state: closed           # ruled ≠ rechecked ≠ closed——16:10 复审通过，四单契约首次完整闭环
closed_at: 2026-10-15T16:10:00+08:00
```

> 首审结论（复审终态）：**通过**——「有条件通过」的 2 项 CAPA 义务于 V1.9.26 批2 清偿并复审验证。
> dogfood 双重验证：①四单契约在真实场景完整走通（委托→发现→裁定→追踪→**复审闭环**，
> ruled 15:25 → closed 16:10·45 分钟生命周期演练）②首审抓出 2 条真改进项且当批清偿——非走过场。
