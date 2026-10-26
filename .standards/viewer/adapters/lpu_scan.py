"""T2 · LPU：.lpu/findings 七维 + docs/lpu 文档 + check_report 对账"""
from . import _exist, _read, _mtime
import os, re

DIMS = {'C-code.md':'C 代码维','D-data.md':'D 数据维','F-function.md':'F 功能维',
        'K-knowledge.md':'K 知识维','P-process.md':'P 流程维',
        'X-conflicts.md':'X 交叉维','RECON.md':'侦察'}

def scan(std_root, proj_root):
    lpu = os.path.join(proj_root, '.lpu')
    if not os.path.isdir(lpu):
        return None
    fdir = os.path.join(lpu, 'findings')
    findings, errors = [], []
    if os.path.isdir(fdir):
        for fn in sorted(os.listdir(fdir)):
            if not fn.endswith('.md'):
                continue
            fp = os.path.join(fdir, fn)
            text = _read(fp)
            anchors = re.findall(r'【证据】([^\s】]+)', text)
            findings.append({'dim': DIMS.get(fn, fn), 'file': f'.lpu/findings/{fn}',
                             'lines': text.count('\n') + 1, 'evidences': len(anchors),
                             'mtime': _mtime(fp)})
    docs = []
    ddir = os.path.join(proj_root, 'docs', 'lpu')
    if os.path.isdir(ddir):
        for fn in sorted(os.listdir(ddir)):
            if fn.endswith('.md'):
                fp = os.path.join(ddir, fn)
                docs.append({'name': fn, 'path': f'docs/lpu/{fn}',
                             'lines': _read(fp).count('\n') + 1, 'mtime': _mtime(fp)})
    report = {}
    cr = os.path.join(lpu, 'check_report.md')
    if os.path.exists(cr):
        t = _read(cr)
        m = re.search(r'结论:\s*\*{0,2}(PASS|WARN|FAIL)\*{0,2}[^\n]*?\(PASS (\d+) / WARN (\d+) / FAIL (\d+)\)', t)
        if m:
            report = {'conclusion': m.group(1), 'pass': int(m.group(2)),
                      'warn': int(m.group(3)), 'fail': int(m.group(4))}
        m2 = re.search(r'置信度分布: A=(\d+) / B=(\d+) / C=(\d+)', t)
        if m2:
            report['confidence'] = f"A={m2.group(1)} / B={m2.group(2)} / C={m2.group(3)}"
    if not findings and not docs:
        return None
    return {'findings': findings, 'dims': len(findings), 'docs': docs,
            'check_report': report, 'errors': errors}
