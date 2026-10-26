"""T4 · UTSS/AEGIS：tests/qa 五件套 + .aegis 三单互锁"""
from . import _read, _mtime
import os, re

FIVE = ['测试计划', '测试用例', '执行记录', '缺陷报告', '准出报告']

def scan(std_root, proj_root):
    qad = os.path.join(proj_root, 'tests', 'qa')
    aeg = os.path.join(proj_root, '.aegis')
    if not os.path.isdir(qad) and not os.path.isdir(aeg):
        return None
    suite = []
    if os.path.isdir(qad):
        files = []
        for root, dirs, fs in os.walk(qad):
            dirs.sort()
            files += [os.path.join(root, f) for f in sorted(fs)]
        for kw in FIVE:
            hit = next((f for f in files if kw in os.path.basename(f)), None)
            suite.append({'item': kw, 'exists': bool(hit),
                          'path': os.path.relpath(hit, proj_root) if hit else ''})
    rc, fp_, vr = [], [], []
    if os.path.isdir(aeg):
        for root, dirs, fs in os.walk(aeg):
            dirs.sort()
            for fn in sorted(fs):
                if not fn.endswith('.md'):
                    continue
                p = os.path.join(root, fn)
                rel = os.path.relpath(p, proj_root)
                t = _read(p)
                tr = re.search(r'trace_ref[:：]\s*([^\s\n]+)', t)
                tid = re.search(r'(RC|FP|VR)[-—]?(\d+)', fn + t[:200])
                rec = {'file': rel, 'mtime': _mtime(p),
                       'trace_ref': tr.group(1) if tr else '', 'id': tid.group(0) if tid else fn}
                if tid and tid.group(1) == 'RC': rc.append(rec)
                elif tid and tid.group(1) == 'FP': fp_.append(rec)
                elif tid and tid.group(1) == 'VR': vr.append(rec)
                else: rc.append(rec)
    def lock_state(rc, fp_, vr):
        if not rc and not fp_ and not vr:
            return 'EMPTY'
        fp_ids = {f['id'] for f in fp_}
        vr_ids = {v['id'] for v in vr}
        complete = all(any(f in fp_ids for f in {r['id']}) for r in rc) and \
                   all(any(v in vr_ids for v in {f['id']} for f in fp_)) and \
                   all(x['trace_ref'] for x in rc + fp_ + vr)
        return 'COMPLETE' if complete else 'DEGRADED'
    tickets = {'rc': rc, 'fp': fp_, 'vr': vr,
               'lock_state': lock_state(rc, fp_, vr)}
    if not suite and not (rc or fp_ or vr):
        return None
    return {'suite': suite, 'tickets': tickets}
