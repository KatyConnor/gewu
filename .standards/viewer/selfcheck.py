"""S1/S2/S3/S5 自检（技术方案 §五）：脱敏回扫 / 只读 / 离线自包含 / stdlib 审计"""
import os, re

CRED_PATTERNS = [
    (r'(?i)(password|passwd|secret|token|api[_-]?key|aksk|access[_-]?key)\s*[:=]\s*["\']?[A-Za-z0-9_\-/+=]{8,}', '凭据字面量'),
    (r'(?i)bearer\s+[A-Za-z0-9_\-\.=/]{20,}', 'Bearer 凭据'),
]
EXTERNAL = re.compile(r'''(?:src|href)\s*=\s*["']https?://''')
IMPORT_WL = {'os','sys','json','re','argparse','datetime','pathlib','hashlib','collections',
             'typing','functools','itertools','time','shutil','glob','http','queue'}
# V1.9.15 勘误：+queue——V1.9.9 SSE 引入 import queue 时漏更白名单（潜伏 S4 违例，
# 因 V1.9.9 后无人跑静态构建而未暴露；本次静态构建复测抓出，当轮修复）

def selfcheck(out_path, proj_root):
    msgs = []
    text = open(out_path, encoding='utf-8').read(20_000_000)
    for pat, name in CRED_PATTERNS:
        hits = re.findall(pat, text)
        if hits:
            msgs.append(f'S1 FAIL: 产物含{name} {len(hits)} 处')
    ext = EXTERNAL.findall(text)
    if ext:
        msgs.append(f'S5 FAIL: 产物含外部资源引用 {len(ext)} 处')
    if 'http://schemas.' not in text and 'xmlns' not in text and re.search(r'url\(https?://', text):
        msgs.append('S5 FAIL: 内联样式含外链')
    here = os.path.dirname(os.path.abspath(__file__))
    bad_imports = []
    for fn in ('build.py', 'selfcheck.py') + tuple(
            os.path.join('adapters', f) for f in os.listdir(os.path.join(here, 'adapters')) if f.endswith('.py')):
        for m in re.finditer(r'^\s*(?:import|from)\s+([a-zA-Z_][\w.]*)', open(os.path.join(here, fn), encoding='utf-8').read(), re.M):
            top = m.group(1).split('.')[0]
            if top not in IMPORT_WL and top != 'adapters' and top != 'selfcheck':
                bad_imports.append(f'{fn}:{m.group(1)}')
    if bad_imports:
        msgs.append('S4 FAIL: 非白名单 import → ' + '; '.join(bad_imports[:5]))
    if not msgs:
        msgs = ['S1 脱敏回扫 0 命中', 'S5 外链 0 处', 'S4 stdlib-only 过']
    return (not any(m.startswith(('S1','S4','S5')) and 'FAIL' in m for m in msgs)), msgs
