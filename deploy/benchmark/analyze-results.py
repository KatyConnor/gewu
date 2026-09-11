#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""基准评测结果终审分析器：读最新 results JSON，产出 43 号规则对比结论。"""
import json
import glob
import sys
from collections import defaultdict

files = sorted(glob.glob('/home/wnn/devcode/ai-code/gewu-platform/deploy/benchmark/results-*.json'))
if len(sys.argv) > 1:
    files = [sys.argv[1]]
path = files[-1]
d = json.load(open(path, encoding='utf-8'))
rows = d.get('rows', [])
print(f"结果文件: {path}")
print(f"总行数: {len(rows)}")

by = defaultdict(lambda: {'ok': 0, 'err': 0, 'scores': [], 'durs': [], 'errs': []})
for r in rows:
    g = r['group']
    if 'error' in r:
        by[g]['err'] += 1
        by[g]['errs'].append(r['error'][:100])
        continue
    by[g]['ok'] += 1
    if r.get('score') is not None:
        by[g]['scores'].append(r['score'])
    if r.get('durationMs') is not None:
        by[g]['durs'].append(r['durationMs'])

print("\n== 分组汇总 ==")
stats = {}
for g in sorted(by):
    s = by[g]
    avg_score = sum(s['scores']) / len(s['scores']) if s['scores'] else None
    avg_dur = sum(s['durs']) / len(s['durs']) if s['durs'] else None
    stats[g] = (avg_score, avg_dur)
    sc = f"{avg_score:.2f}" if avg_score is not None else 'N/A'
    du = f"{avg_dur:.0f}ms" if avg_dur is not None else 'N/A'
    print(f"  {g:12s} 成功 {s['ok']:3d} 失败 {s['err']:3d} | 评分均值 {sc:>5s} | 平均时长 {du}")

# 分类维度细分（knowledge_qa / code_gen / analysis / task_planning）
cats = defaultdict(lambda: defaultdict(list))
for r in rows:
    if 'error' not in r and r.get('score') is not None and r.get('category'):
        cats[r['category']][r['group']].append(r['score'])
print("\n== 分类维度评分均值 ==")
all_cats = sorted({c for c_map in cats.values() for c in c_map})
header = f"{'类别':14s}" + ''.join(f"{g:>14s}" for g in sorted(by))
print(header)
for c in all_cats:
    line = f"{c:14s}"
    for g in sorted(by):
        scores = cats[c].get(g, [])
        avg = sum(scores) / len(scores) if scores else None
        line += f"{('%.2f' % avg) if avg is not None else 'N/A':>14s}"
    print(line)

# 43 号规则对比（legacy 为基线）
if 'legacy' in stats and stats['legacy'][0]:
    base_score = stats['legacy'][0]
    base_dur = stats['legacy'][1]
    print("\n== 43 号规则对比（基线=legacy）==")
    for g in sorted(by):
        if g == 'legacy' or g not in stats:
            continue
        sc, du = stats[g]
        if base_score and sc:
            delta_q = (sc - base_score) / base_score * 100
        else:
            delta_q = None
        delta_c = ((du - base_dur) / base_dur * 100) if (du and base_dur) else None
        dq = f"{delta_q:+.1f}%" if delta_q is not None else 'N/A'
        dc = f"{delta_c:+.1f}%" if delta_c is not None else 'N/A'
        print(f"  {g:12s} Δ质量={dq} Δ时长={dc}")
    print("\n规则: Δ质量>=+5% 且 Δ成本/时长<=+20% -> 接线; Δ质量<+2% 或 Δ成本>50% -> 裁剪; 中间 -> 缩小范围")
