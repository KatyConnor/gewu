"""T6 · 进度面板：各域 progress/PROGRESS.md → done/total/pct"""
from . import _read, _mtime
import os, re

def scan(std_root, proj_root):
    panels = []
    cands = [
        ('LPU 逆向理解', os.path.join(proj_root, '.lpu', 'progress', 'PROGRESS.md')),
        ('LR 重构实施', os.path.join(proj_root, '.lr', 'progress', 'PROGRESS.md')),
        ('OPS 生产运维', os.path.join(proj_root, '.ops', 'progress', 'PROGRESS.md')),
    ]
    for domain, fp in cands:
        if not os.path.exists(fp):
            continue
        t = _read(fp)
        boxes = re.findall(r'-\s*\[([ xX])\]\s*(.+)', t)
        done = sum(1 for c, _ in boxes if c in 'xX')
        stage = re.search(r'当前阶段[:：]\s*([^\n]+)', t)
        issues = len(re.findall(r'^- ', t.split('## 未决问题')[-1].split('##')[0], re.M))
        panels.append({'domain': domain, 'file': os.path.relpath(fp, proj_root),
                       'done': done, 'total': len(boxes),
                       'pct': round(done * 100 / len(boxes)) if boxes else 0,
                       'stage': stage.group(1).strip()[:70] if stage else '',
                       'open_issues': issues, 'mtime': _mtime(fp)})
    if not panels:
        return None
    return {'panels': panels}
