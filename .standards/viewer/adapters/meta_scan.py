"""T5 · META：meta/records/*.yaml → 判型记录表（受限解析器·六字段）"""
from . import _mtime
import os, re

def _mini_yaml(text):
    """受限解析：仅取顶层标量字段（判型记录字段规整·解析失败字段容忍为 None）"""
    out, key = {}, None
    for ln in text.splitlines():
        if ln.lstrip().startswith('#') or not ln.strip():
            continue
        if not ln.startswith((' ', '\t')) and ':' in ln:
            k, _, v = ln.partition(':')
            k, v = k.strip(), v.strip().strip('"\'')
            if v:
                out[k] = v
            key = k
        elif key and ln.startswith((' ', '\t')) and ':' in ln and not out.get(key):
            k2, _, v2 = ln.strip().partition(':')
            v2 = v2.strip().strip('"\'')
            if k2 in ('q1_harm', 'q2_shall_verifiable', 'q3_multisol', 'q4_domain_occupied') and v2:
                out[f'form_{k2}'] = v2
    return out

def scan(std_root, proj_root):
    rdir = os.path.join(std_root, 'meta', 'records')
    if not os.path.isdir(rdir):
        return None
    records, errors = [], []
    for fn in sorted(os.listdir(rdir)):
        if not fn.endswith('.yaml'):
            continue
        try:
            y = _mini_yaml(open(os.path.join(rdir, fn), encoding='utf-8').read())
        except OSError:
            errors.append(fn); continue
        cid = y.get('content_id', '')
        spec = re.sub(r'（.*', '', cid)
        deepening = y.get('form_q4_domain_occupied', '') in ('true', 'True')
        records.append({
            'name': fn[:-5], 'spec': spec, 'content_id': cid,
            'source': y.get('source', '?'), 'version': y.get('version', '?'),
            'breaking': y.get('breaking', '?'), 'deepening': deepening,
            'q3': y.get('form_q3_multisol', '?'),
            'decided_by': y.get('decided_by', '')[:60],
            'mtime': _mtime(os.path.join(rdir, fn))})
    if not records:
        return None
    recs = [r for r in records if r['name'].startswith('rev-')]
    return {'records': records, 'count': len(records),
            'revisions': len(recs), 'errors': errors}
