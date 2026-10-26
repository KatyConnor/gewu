"""T1 · AETC 留痕层：audit/traces/*.jsonl → 会话时间线 + 哈希链校验 + 检查点/回滚"""
from . import _exist, _read, _mtime, _sha
import os, json

EVENT_TYPES = {'TASK_OPEN','PLAN_COMMIT','STEP_DONE','DECISION','SKILL_USE','RISK_PRE',
               'RISK_POST','CHECKPOINT','GATE_REQUEST','GATE_RESULT','BLOCKED',
               'CORRECTION','TASK_CLOSE'}
R3_EVENTS = {'RISK_PRE','RISK_POST','GATE_REQUEST'}

def _ev_summary(e):
    for k in ('summary','description','desc','action','task','result','content','detail','message'):
        if k in e and e[k]:
            s = str(e[k]).strip().replace('\n',' ')
            return (s[:120] + '…') if len(s) > 120 else s
    for k in ('plan','steps','output','reason','conclusion'):
        if k in e and e[k]:
            return f"[{k}] " + str(e[k])[:100].replace('\n',' ')
    return ''

def _hash_ok(lines):
    """哈希链校验：若记录含 prev/hash 字段则逐条验证链接一致性；无字段→None(未启用)"""
    prev, seen = None, 0
    for e in lines:
        h = e.get('hash') or e.get('record_hash')
        p = e.get('prev') or e.get('prev_hash')
        if h is None and p is None:
            continue
        if prev is not None and p and p != prev:
            return False
        prev = h; seen += 1
    return True if seen > 0 else None

def scan(std_root, proj_root):
    tdir = os.path.join(proj_root, 'audit', 'traces')
    if not os.path.isdir(tdir):
        return None
    sessions, errors = [], []
    for fn in sorted(os.listdir(tdir)):
        if not fn.endswith('.jsonl'):
            continue
        fp = os.path.join(tdir, fn)
        lines, bad = [], 0
        for ln in _read(fp).splitlines():
            ln = ln.strip()
            if not ln:
                continue
            try:
                lines.append(json.loads(ln))
            except ValueError:
                bad += 1
        if not lines:
            continue
        events = []
        for e in lines:
            et = e.get('type', 'other')
            events.append({'ts': e.get('ts') or e.get('time') or e.get('timestamp') or '?',
                           'type': et if et in EVENT_TYPES else 'other',
                           'phase': e.get('phase') or e.get('stage') or '',
                           'gate': e.get('gate') or '', 'r3': et in R3_EVENTS,
                           'summary': _ev_summary(e)})
        types = [e['type'] for e in events]
        state = ('CLOSED' if 'TASK_CLOSE' in types else
                 'INTERRUPTED' if ('BLOCKED' in types or 'CORRECTION' in types) else 'OPEN')
        sessions.append({'id': fn[:-6], 'file': f'audit/traces/{fn}', 'mtime': _mtime(fp),
                         'events': events, 'event_count': len(events), 'unknown': types.count('other'),
                         'r3_count': sum(1 for e in events if e['r3']),
                         'gates': sum(1 for t in types if t.startswith('GATE')),
                         'state': state, 'hash_ok': _hash_ok(lines)})
        if bad:
            errors.append(f'{fn}: {bad} 行解析失败(已跳过)')
    cps, rollbacks = [], []
    cdir = os.path.join(proj_root, 'audit', 'checkpoints')
    if os.path.isdir(cdir):
        for d in sorted(os.listdir(cdir))[:500]:
            fp = os.path.join(cdir, d)
            if not os.path.isdir(fp):
                continue
            cps.append({'id': d, 'mtime': _mtime(fp),
                        'files': sum(len(fs) for _, _, fs in os.walk(fp))})
    rdir = os.path.join(proj_root, 'audit', 'reviews')
    if os.path.isdir(rdir):
        for fn in sorted(os.listdir(rdir)):
            if fn.endswith('.md'):
                rollbacks.append({'file': f'audit/reviews/{fn}', 'mtime': _mtime(os.path.join(rdir, fn))})
    if not sessions and not cps and not rollbacks:
        return None
    return {'sessions': sessions, 'checkpoints': cps, 'rollbacks': rollbacks,
            'stats': {'sessions': len(sessions),
                      'events': sum(s['event_count'] for s in sessions),
                      'r3': sum(s['r3_count'] for s in sessions),
                      'interrupted': sum(1 for s in sessions if s['state'] == 'INTERRUPTED')},
            'errors': errors}
