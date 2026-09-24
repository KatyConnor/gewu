# 人工复核记录（Review Checklist）

<!--
  使用方式：复制本模板到 audit/reviews/，命名 review-<日期>-<主题>.md
  复核对象：R2/R3 操作产出、G1~G5 审批门产物、R1 抽查产出
  结论取值：pass / pass-with-notes / fail（fail 触发返工或回退流程）
-->

## 基本信息
- 复核编号：REV-YYYYMMDD-NN
- 复核对象：（文件/变更/报告路径，或会话ID）
- 关联留痕：（session-id 及相关 seq）
- 风险级别：R1 / R2 / R3
- 复核人：（姓名/角色，不能与请求人相同）
- 复核时间：

## 留痕完整性核查
- [ ] 留痕记录覆盖任务全流程（TASK_OPEN→TASK_CLOSE 闭环）
- [ ] STEP_DONE 均含 分析/技能/执行 三段
- [ ] R2/R3 操作均有 RISK_PRE + RISK_POST 配对
- [ ] 应审批操作均有 GATE_REQUEST + GATE_RESULT 配对
- [ ] 哈希链校验通过（audit_check.py 无 ERROR）

## 产出质量核查
- [ ] 变更与任务目标一致，无越界改动（diff 最小性）
- [ ] 验证证据充分（测试/构建/冒烟结果可复现）
- [ ] 无敏感信息泄露（密钥/口令/个人数据）
- [ ] 遗留问题与风险已如实声明

## 结论与处理
- 结论：pass / pass-with-notes / fail
- 备注（问题描述、位置、严重级）：
  1.
  2.
- 后续动作（返工范围 / 回退检查点 / 新增规则提案）：

---
复核人签字/确认方式：
