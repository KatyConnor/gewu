# audit/ 目录说明（AETC 审计资产）

本目录是 AETC 规范（AETC-STD-001）在本项目的执行载体，属于审计资产，
禁止删除、改写其中已有记录（见规范红线清单）。

```
audit/
├── traces/        执行留痕（JSONL，每会话一个文件；hooks/ 子目录为钩子自动留痕）
├── checkpoints/   检查点（git ref 快照的登记表；非git目录的tar归档也存这里）
├── reviews/       人工复核记录、审批单、回退记录
├── reports/       会话报告、复盘报告、notes/ 长文附件
├── rules/         项目级管控规则（00-core + 01~07 阶段规则）
├── tools/         留痕工具链（audit_log / audit_check / audit_report / checkpoint）
├── templates/     复核Checklist、审批单、复盘报告、回退记录模板
└── config.json    管控配置（审批人、额外风险清单、禁用命令）
```

常用命令：
```bash
python3 audit/tools/audit_log.py TASK_OPEN --phase coding --summary "..."    # 写留痕
python3 audit/tools/audit_check.py                                           # 校验完整性
python3 audit/tools/audit_report.py --all                                    # 生成会话报告
bash   audit/tools/checkpoint.sh create cp-xxx                               # 创建检查点
```

人工复核入口：audit/reviews/（模板在 audit/templates/）。
