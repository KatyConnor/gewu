#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
质量门禁脚本（P4-D · 方案 B：PR/合并门禁本地比对，零 LLM 成本）。

用法：
    python3 scripts/quality-gate.py [--dir tests/quality] [--strict]

行为：
    读取 {dir}/gate-state.json（由定时跑批 QualityBaselineRunner 写入），
    输出门禁结论并以退出码供 CI 判定：
        0 = PASS / no-baseline（首轮热身放行并告警）
        1 = FAIL（评分跌破容差，阻断合并——处置见 latest-run.json 与 baseline.json）
        2 = 配置/文件缺失（strict 模式阻断；默认放行并告警）

CI 接线示例（Jenkinsfile stage）：
    sh 'python3 scripts/quality-gate.py --strict'
"""
import argparse
import json
import os
import sys

def main():
    parser = argparse.ArgumentParser(description="质量基准门禁（P4-D）")
    parser.add_argument("--dir", default="tests/quality", help="基准/门禁文件目录")
    parser.add_argument("--strict", action="store_true", help="文件缺失时也阻断（默认放行并告警）")
    args = parser.parse_args()

    gate_path = os.path.join(args.dir, "gate-state.json")
    if not os.path.exists(gate_path):
        print(f"[QUALITY-GATE][WARN] 门禁状态文件不存在: {gate_path}（跑批未执行过？）")
        sys.exit(1 if args.strict else 0)

    with open(gate_path, encoding="utf-8") as f:
        gate = json.load(f)

    verdict = gate.get("verdict", "UNKNOWN")
    print(f"[QUALITY-GATE] verdict={verdict} checkedAt={gate.get('checkedAt')}")
    print(f"[QUALITY-GATE] detail={gate.get('detail')}")
    for name, info in (gate.get("regressions") or {}).items():
        print(f"[QUALITY-GATE] REGRESSION {name}: baseline={info.get('baseline')} "
              f"latest={info.get('latest')} drop={info.get('dropPct')}%")

    if verdict == "PASS":
        sys.exit(0)
    print("[QUALITY-GATE][BLOCK] 质量评分跌破容差，阻断合并——按 45 号预承诺规则复核后处置")
    sys.exit(1)

if __name__ == "__main__":
    main()
