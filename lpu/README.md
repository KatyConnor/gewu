# LPU 能力安装目录说明（lpu/）

此目录为 LPU 规范（LPU-STD-001 V1.0）的**能力本体**，随安装包放置在项目根目录：

```
lpu/                       # 静态能力（随包安装，只读）
├── README.md              # 本文件
├── rules/                 # 11 份规则文件（00-core 必读，其余按阶段加载）
├── templates/             # 8 份成文文档模板（S4 使用）
├── scripts/lpu_check.py   # 交付自检脚本（S4 后运行）
└── init/                  # 运行时工作区初始化模板（S0 时复制为 .lpu/）
    ├── config.json        #   → .lpu/config.json
    ├── progress/PROGRESS.md  # → .lpu/progress/PROGRESS.md
    └── findings/README.md #   → .lpu/findings/README.md
```

`.lpu/`（点前缀）与 `lpu/` 的分工：
- `lpu/` = 规则与模板（静态，装一次）
- `.lpu/` = 本项目的分析进度与发现（动态，Agent 初始化与维护）

规则与模板不在 `.lpu/`，findings 只存**证据与结论**，不存规则本身。

## 人工使用指引
- 想知道"Agent 分析到哪了" → 看 `.lpu/progress/PROGRESS.md`
- 想核对某条结论的依据 → 到 `.lpu/findings/` 对应维度文件搜关键词，看【证据】行
- 最终成文文档输出到项目 `docs/lpu/` 目录

## 目录约定
- `.lpu/`（运行时工作区）建议加入 `.gitignore`（分析过程数据不入库；团队要共享成果可选择性提交 findings/）
- 删除 `.lpu/` = 清空分析进度重新开始（已生成的成文文档不受影响）
- `lpu/`（本目录）随版本更新替换，不存放任何项目数据
