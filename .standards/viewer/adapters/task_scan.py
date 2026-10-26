"""T3 · LR/OPS：.lr 改造记录 + .ops 事件单"""
from . import _read, _mtime
import os, re

def _md_files(d):
    out = []
    for root, dirs, files in os.walk(d):
        dirs.sort()
        for fn in sorted(files):
            if fn.endswith('.md'):
                fp = os.path.join(root, fn)
                rel = os.path.relpath(fp, os.path.dirname(d))
                out.append({'file': rel, 'mtime': _mtime(fp),
                            'lines': _read(fp).count('\n') + 1})
    return out

def scan(std_root, proj_root):
    lr, ops = None, None
    lrd = os.path.join(proj_root, '.lr')
    if os.path.isdir(lrd):
        recs = _md_files(lrd)
        gates = {}
        cr = os.path.join(lrd, 'lr-check-report.md')
        if os.path.exists(cr):
            t = _read(cr)
            gates = {'file': '.lr/lr-check-report.md',
                     'pass': len(re.findall(r'PASS', t)),
                     'fail': len(re.findall(r'\bFAIL\b', t))}
        if recs or gates:
            lr = {'records': recs[:200], 'check': gates}
    opsd = os.path.join(proj_root, '.ops')
    if os.path.isdir(opsd):
        recs = _md_files(opsd)
        incidents = []
        for r in recs:
            t = _read(os.path.join(proj_root, r['file']))
            st = re.search(r'状态[:：]\s*(\S+)', t)
            sev = re.search(r'(P0|P1|P2|S\d|严重|高|中|低)', t)
            incidents.append({**r, 'state': st.group(1) if st else '?',
                              'severity': sev.group(1) if sev else '?'})
        ops = {'incidents': incidents[:200], 'count': len(incidents)}
    if not lr and not ops:
        return None
    return {'lr': lr, 'ops': ops}
