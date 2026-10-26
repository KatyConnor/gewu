#!/usr/bin/env python3
"""STD-HUB-VIEW-001 · 留痕产物查看器构建入口（一期静态单文件 + 二期 M4/M5/M6）
用法：
  python3 .standards/viewer/build.py                          # 扫当前项目 → .viewer/index.html
  python3 .standards/viewer/build.py --project PATH[,PATH2…]  # 指定项目根（M5：逗号分隔多项目）
  python3 .standards/viewer/build.py --dryrun                 # 只扫描不写文件，输出统计 JSON
  python3 .standards/viewer/build.py --snapshot               # M6：构建后存 view-model 快照 + 嵌 delta
  python3 .standards/viewer/build.py --serve [--port 8777]    # M4：127.0.0.1 动态服务（mtime 缓存·零写）
技术方案 §2/§4.3 · 实施计划 M0-M6 · 全链只读（静态模式唯一写路径=--out/--snapshot，均限 .viewer/）
"""
import argparse, json, os, re, sys, time
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from adapters import (trace_scan, lpu_scan, task_scan, test_scan, meta_scan, prog_scan)

SCHEMA_VERSION = '1.0'
STD_LIB_OK = True  # S4 stdlib-only 由 G11-3 与 selfcheck 审计

# mtime 指纹探测目录（M4 缓存失效判定——只 stat 不读内容）
PROBE_DIRS = ['audit/traces', 'audit/checkpoints', 'audit/reviews', '.lpu', '.lr', '.ops',
              'tests/qa', '.aegis']
SNAP_KEEP = 10  # M6 快照保留份数


def resolve_roots(project, std_override=None):
    """两级探测铁律（技术方案 §2.1）：项目根 .standards 优先 → ~/.std-hub 兜底"""
    if std_override:
        return project, std_override, 'override'
    p = os.path.join(project, '.standards')
    if os.path.isdir(p):
        return project, p, 'project'
    g = os.path.expanduser('~/.std-hub/.standards')
    if os.path.isdir(g):
        return project, g, 'global'
    return project, None, 'none'


def collect(proj_root, std_root):
    vm = {'schema_version': SCHEMA_VERSION,
          'meta': {'project_root': os.path.abspath(proj_root),
                   'name': os.path.basename(os.path.abspath(proj_root)),
                   'two_level': 'project' if std_root and os.path.dirname(std_root) == os.path.abspath(proj_root) else
                                ('global' if std_root and '.std-hub' in std_root else ('override' if std_root else 'none')),
                   'hub_version': _hub_version(std_root), 'built_at': datetime.now().strftime('%Y-%m-%d %H:%M:%S')},
          'sessions': None, 'domains': None, 'tests': None,
          'governance': None, 'panels': None, 'stats': {}, 'errors': []}
    runs = [('trace', trace_scan), ('lpu', lpu_scan), ('task', task_scan),
            ('test', test_scan), ('meta', meta_scan), ('prog', prog_scan)]
    for tag, mod in runs:
        try:
            res = mod.scan(std_root, proj_root) if std_root or tag in ('trace', 'lpu', 'task', 'test', 'prog') else None
        except Exception as ex:  # 容错铁律：scanner 崩溃降级，不 FAIL 构建
            vm['errors'].append(f'{tag}_scan: {type(ex).__name__}: {ex}')
            continue
        if res is None:
            continue
        vm['errors'] += res.pop('errors', [])
        if tag == 'trace':
            vm['sessions'] = res.pop('sessions', []); vm['checkpoints'] = res.pop('checkpoints', [])
            vm['rollbacks'] = res.pop('rollbacks', []); vm['trace_stats'] = res.pop('stats', {})
        elif tag == 'lpu':
            vm['domains'] = vm['domains'] or {}; vm['domains']['lpu'] = res
        elif tag == 'task':
            vm['domains'] = vm['domains'] or {}; vm['domains']['lr'] = res.get('lr'); vm['domains']['ops'] = res.get('ops')
        elif tag == 'test':
            vm['tests'] = res
        elif tag == 'meta':
            vm['governance'] = res
        elif tag == 'prog':
            vm['panels'] = res.get('panels')
    st = vm['stats']
    st['sessions'] = len(vm.get('sessions') or [])
    st['events'] = (vm.get('trace_stats') or {}).get('events', 0)
    st['r3'] = (vm.get('trace_stats') or {}).get('r3', 0)
    st['hash_ok'] = all(s.get('hash_ok') is not False for s in (vm.get('sessions') or []))
    arts = 0
    arts += len(vm.get('checkpoints') or [])
    lpu = ((vm.get('domains') or {}).get('lpu') or {})
    arts += len(lpu.get('findings') or []) + len(lpu.get('docs') or [])
    doms = vm.get('domains') or {}
    arts += len(((doms.get('lr') or {}).get('records')) or []) + len(((doms.get('ops') or {}).get('incidents')) or [])
    tk = (vm.get('tests') or {}).get('tickets') or {}
    arts += len(tk.get('rc') or []) + len(tk.get('fp') or []) + len(tk.get('vr') or [])
    arts += len((vm.get('governance') or {}).get('records') or [])
    arts += len(vm.get('panels') or [])
    st['artifacts'] = arts
    st['errors'] = len(vm['errors'])
    return vm


def _hub_version(std_root):
    if not std_root:
        return '?'
    for cand in (os.path.join(os.path.dirname(std_root), 'README.md'),):
        try:
            for ln in open(cand, encoding='utf-8').read(20000).splitlines():
                if ln.startswith('| V') and 'V1.9' in ln:
                    return ln.split('|')[1].strip()
        except OSError:
            continue
    return '?'


class ProjectList(list):
    """可携带 ws（workspace 目录）属性的 list——json.dumps 只序列化元素，ws 不落数据岛"""
    ws = ()


def _is_project(path):
    """项目特征：含任一产物/规范特征目录即视为项目根（否则按父目录展开）"""
    feats = ['.standards', 'audit', '.lpu', '.lr', '.ops', 'tests', '.aegis', '.viewer']
    return any(os.path.exists(os.path.join(path, f)) for f in feats)


def discover_projects(ws_dir):
    """V1.9.7 父目录语义：一级子目录中含项目特征者纳入（新建项目免重启的关键）"""
    found = []
    try:
        subs = sorted(os.listdir(ws_dir))
    except OSError:
        return found
    for s in subs:
        fp = os.path.join(ws_dir, s)
        if os.path.isdir(fp) and _is_project(fp) and not s.startswith('.'):
            found.append(fp)
    return found


def collect_all(project_args, std_override):
    """M5 多项目聚合：projects=[vm,...]（每 vm 完整独立，shell 端切换）
    V1.9.7：路径无项目特征 → 视为 workspace 父目录自动发现子项目（挂 vms.ws 供指纹监控）"""
    vms = ProjectList()
    ws_dirs = []
    for p in project_args:
        proj = os.path.abspath(p.strip())
        if not os.path.isdir(proj):
            print(f'路径不存在，跳过：{proj}', file=sys.stderr)
            continue
        if not _is_project(proj):
            ws_dirs.append(proj)
            for sub in discover_projects(proj):
                _collect_one(vms, sub, std_override)
            continue
        _collect_one(vms, proj, std_override)
    vms.ws = ws_dirs  # list 属性不进 json.dumps——指纹监控发现面用
    return vms


def _collect_one(vms, proj, std_override):
    proj, std, level = resolve_roots(proj, std_override)
    vm = collect(proj, std)
    vm['meta']['two_level'] = level
    vm['meta']['name'] = os.path.basename(os.path.normpath(proj))
    vms.append(vm)


def _lost_vm(path):
    """失联项目 stub（T8）：路径不存在→灰态呈现（不删行·移回自动恢复）"""
    name = os.path.basename(os.path.normpath(path)) or path
    return {'schema_version': SCHEMA_VERSION,
            'meta': {'name': name, 'project_root': path, 'two_level': 'lost',
                     'hub_version': '?', 'built_at': '', 'lost': True},
            'sessions': None, 'domains': None, 'tests': None, 'governance': None,
            'panels': None, 'checkpoints': None, 'rollbacks': None,
            'trace_stats': {}, 'errors': [],
            'stats': {'sessions': 0, 'events': 0, 'r3': 0, 'hash_ok': True,
                      'artifacts': 0, 'errors': 0}}


def registry_factory(std_override=None):
    """V1.9.8 注册表驱动 serve 工厂（daemon.py 用）：每请求重读 projects.list
    失联路径→灰态 stub；两级行存续（有 .standards 的项目走既有 resolve）"""
    reg = os.path.expanduser('~/.std-hub/registry/projects.list')
    rows = []
    if os.path.exists(reg):
        rows = [l.strip() for l in open(reg, encoding='utf-8').read().splitlines()
                if l.strip() and not l.startswith('#')]
    vms = ProjectList()
    for r in rows:
        if os.path.isdir(r):
            _collect_one(vms, r, std_override)
        else:
            vms.append(_lost_vm(r))
    vms.reg = reg  # 指纹发现面：注册表文件本身（append 行→指纹变→免重启出现）
    return vms


def fingerprint(vms):
    """M4 缓存指纹：全部探测目录内 (mtime,size) 聚合——变更即触发重扫
    V1.9.7：workspace 父目录的一级子目录清单纳入指纹（新建/删除项目免重启发现）"""
    import hashlib
    h = hashlib.sha256()
    reg = getattr(vms, 'reg', None)
    if reg and os.path.exists(reg):
        st = os.stat(reg)
        h.update(f'reg:{st.st_mtime}:{st.st_size}'.encode())
    for ws in getattr(vms, 'ws', []) or []:
        try:
            subs = sorted(s for s in os.listdir(ws) if os.path.isdir(os.path.join(ws, s)))
            for s in subs:
                fp = os.path.join(ws, s)
                h.update(s.encode())
                h.update(str(os.stat(fp).st_mtime).encode())
        except OSError:
            pass
    for vm in vms:
        root = vm['meta']['project_root']
        std = None
        h.update(root.encode())
        for rel in PROBE_DIRS:
            d = os.path.join(root, rel)
            if not os.path.isdir(d):
                continue
            for base, dirs, files in os.walk(d):
                dirs.sort()
                for fn in sorted(files):
                    fp = os.path.join(base, fn)
                    try:
                        st = os.stat(fp)
                        h.update(f'{fp}:{st.st_mtime_ns}:{st.st_size}'.encode())
                    except OSError:
                        pass
        # std 侧判型记录也入指纹（--std override 或项目级）
        for cand in (os.path.join(root, '.standards', 'meta', 'records'),):
            if os.path.isdir(cand):
                for fn in sorted(os.listdir(cand)):
                    fp = os.path.join(cand, fn)
                    try:
                        st = os.stat(fp)
                        h.update(f'{fp}:{st.st_mtime_ns}:{st.st_size}'.encode())
                    except OSError:
                        pass
    return h.hexdigest()[:16]


# ── M6 快照 ──
def snap_dir(proj_root):
    return os.path.join(proj_root, '.viewer', 'snapshots')


def latest_snapshot(proj_root):
    d = snap_dir(proj_root)
    if not os.path.isdir(d):
        return None
    snaps = sorted(f for f in os.listdir(d) if f.startswith('snap-') and f.endswith('.json'))
    if not snaps:
        return None
    try:
        return json.loads(open(os.path.join(d, snaps[-1]), encoding='utf-8').read())
    except (OSError, ValueError):
        return None


def apply_delta(vm):
    """嵌入相对最近快照的 delta（总览 KPI 区显示）"""
    prev = latest_snapshot(vm['meta']['project_root'])
    if not prev:
        vm['meta']['delta'] = None
        return
    ps = prev.get('stats') or {}
    cs = vm['stats']
    vm['meta']['delta'] = {'since': prev.get('meta', {}).get('built_at', '?'),
                           'sessions': cs.get('sessions', 0) - ps.get('sessions', 0),
                           'events': cs.get('events', 0) - ps.get('events', 0),
                           'artifacts': cs.get('artifacts', 0) - ps.get('artifacts', 0),
                           'r3': cs.get('r3', 0) - ps.get('r3', 0)}


def save_snapshot(vms, out_path):
    for vm in vms:
        d = snap_dir(vm['meta']['project_root'])
        os.makedirs(d, exist_ok=True)
        fp = os.path.join(d, f'snap-{datetime.now().strftime("%Y%m%d-%H%M%S")}.json')
        with open(fp, 'w', encoding='utf-8') as f:
            json.dump(vm, f, ensure_ascii=False, separators=(',', ':'))
        snaps = sorted(x for x in os.listdir(d) if x.startswith('snap-'))
        for old in snaps[:-SNAP_KEEP]:
            try:
                os.remove(os.path.join(d, old))
            except OSError:
                pass


def render_html(vms, serve_mode=False, out_dir=None):
    """渲染层：数据岛 {projects:[vm,...]} 内嵌单文件 shell（v1 数据兼容单 vm 顶层）
    V1.9.15：+file_serve/out_dir——文件链接模式感知（serve=/f 路由·静态=每项目相对基）"""
    tpl = os.path.join(HERE, 'template', 'shell.html')
    html = open(tpl, encoding='utf-8').read()
    if serve_mode:
        file_serve = '/f'                       # serve 模式：/f/<项目序号>/<相对路径>
    else:
        file_serve = None
        for i, vm in enumerate(vms):            # 静态模式：每项目算相对基（vms[0] 恒 '..'）
            base = '..' if i == 0 else os.path.relpath(
                vm['meta']['project_root'], out_dir or os.path.join(
                    vms[0]['meta']['project_root'], '.viewer')).replace(os.sep, '/')
            vm['meta']['file_base'] = base
    data = json.dumps({'projects': vms, 'built_at': datetime.now().strftime('%Y-%m-%d %H:%M:%S'),
                       'file_serve': file_serve},
                      ensure_ascii=False, separators=(',', ':'))
    html = html.replace('/*__VIEW_MODEL__*/null', data, 1)
    return html


# ── V1.9.15 只读文件服务（serve 模式 /f 路由：findings/会话原文等文件链接的落点）──
_FILE_WHITELIST = ('.lpu/', '.lr/', '.ops/', '.aegis/', 'audit/', 'docs/',
                   'tests/', 'atlas/', '.atlas/', 'suite-check-report.md')


def _unq(s):
    """极简 percent-decode（不引 urllib——S4 import 白名单纪律零新依赖）"""
    if '%' not in s:
        return s
    out, i, n = [], 0, len(s)
    while i < n:
        if s[i] == '%' and i + 2 < n:
            try:
                out.append(chr(int(s[i + 1:i + 3], 16)))
                i += 3
                continue
            except ValueError:
                pass
        out.append(s[i])
        i += 1
    return ''.join(out)


def _mask(text):
    """S1 同源脱敏：服务文件内容过凭据正则再出网（与产物面同一纪律）"""
    from selfcheck import CRED_PATTERNS
    for pat, _name in CRED_PATTERNS:
        text = re.sub(pat, lambda m: '***', text)
    return text


def serve(vms_factory, port, snapshot, ttl=0, pid_path=None, state_path=None, event_bus=None, watcher=None):
    """M4 动态服务：127.0.0.1 专用 · mtime 缓存（指纹变更才重扫）· serve 模式零写盘
    V1.9.8（VIEW-002）：ttl>0 时空闲自停（最后 HTTP 请求起算·注册流量不续命 D5）；
    pid_path/state_path 由 daemon.py 传入（优雅停时清理）。vms_factory(std_override) -> vms"""
    import hashlib
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    state = {'fp': None, 'html': '', 'rebuilt': 0, 't_ms': 0.0, 'projects': 0,
             'last_http': time.time(), 'pid': os.getpid(), 'registry_rows': 0,
             'vms': None, 'sse_clients': set(), 'watcher': watcher}

    def rebuild():
        t0 = time.time()
        vms = vms_factory(None)
        state['fp'] = fingerprint(vms)
        state['html'] = render_html(vms, serve_mode=True)
        state['vms'] = vms
        state['projects'] = len(vms)
        state['rebuilt'] += 1
        state['t_ms'] = round((time.time() - t0) * 1000, 1)
        state['registry_rows'] = len(getattr(vms, 'reg_rows', []) or []) or len(vms)
        state['last_http'] = time.time()  # 重建=有流量（人看才续命）

    rebuild()

    class H(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def _sse(self):
            """SSE 流（W5）：订阅 event_bus 广播；15s 心跳；断连清理。
            ThreadingHTTPServer 每连接一线程——上限由 daemon 层控制（连接数>4 拒新）"""
            if len(state['sse_clients']) >= 4:
                return self._send(503, b'too many sse clients', 'text/plain')
            import queue as _q
            q = _q.Queue(maxsize=32)
            state['sse_clients'].add(q)
            try:
                self.send_response(200)
                self.send_header('Content-Type', 'text/event-stream; charset=utf-8')
                self.send_header('Cache-Control', 'no-cache')
                self.send_header('Connection', 'keep-alive')
                self.end_headers()
                self.wfile.write(b': connected\n\n')
                self.wfile.flush()
                while True:
                    try:
                        ev = q.get(timeout=15)
                        body = json.dumps(ev, ensure_ascii=False, separators=(',', ':'))
                        self.wfile.write(f'event: invalidate\ndata: {body}\n\n'.encode('utf-8'))
                    except _q.Empty:
                        self.wfile.write(b': ping\n\n')  # 心跳防中间层断连
                    self.wfile.flush()
                    state['last_http'] = time.time()  # SSE 活连接=人在看（续命）
            except (BrokenPipeError, ConnectionResetError):
                pass
            finally:
                state['sse_clients'].discard(q)

        def _send(self, code, body, ctype):
            self.send_response(code)
            self.send_header('Content-Type', ctype)
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _serve_file(self, rest):
            """V1.9.15 只读文件路由：/f/<项目序号>/<相对路径>
            纪律：白名单前缀（运行时产物目录）+ realpath 防穿越 + 1MB 上限 + S1 同源脱敏"""
            parts = _unq(rest).split('/', 1)
            if len(parts) != 2 or not parts[0].isdigit() or not parts[1]:
                return self._send(404, b'not found', 'text/plain')
            idx, rel = int(parts[0]), parts[1].lstrip('/')
            vms = state['vms'] or []
            if not (0 <= idx < len(vms)):
                return self._send(404, b'not found', 'text/plain')
            root = (vms[idx].get('meta', {}) or {}).get('project_root', '')
            if not root or not (rel.startswith(_FILE_WHITELIST)):
                return self._send(403, b'forbidden (whitelist)', 'text/plain')
            full = os.path.realpath(os.path.join(root, rel))
            rroot = os.path.realpath(root)
            if not full.startswith(rroot + os.sep) or not os.path.isfile(full):
                return self._send(404, b'not found', 'text/plain')
            try:
                if os.path.getsize(full) > 1_000_000:
                    return self._send(413, b'too large', 'text/plain')
                text = open(full, encoding='utf-8', errors='replace').read()
            except OSError:
                return self._send(404, b'not found', 'text/plain')
            self._send(200, _mask(text).encode('utf-8'), 'text/plain; charset=utf-8')

        def do_GET(self):
            if self.path == '/healthz':
                body = json.dumps({'ok': True, 'projects': state['projects'],
                                   'rebuilt': state['rebuilt'], 'rebuild_ms': state['t_ms'],
                                   'fingerprint': state['fp'], 'pid': state['pid'],
                                   'port': port,
                                   'registry_rows': state['registry_rows'],
                                   'last_seen': int(state['last_http']),
                                   'uptime_s': int(time.time() - state.get('t0', time.time())),
                                   'ttl_left_s': int(ttl - (time.time() - state['last_http'])) if ttl else -1,
                                   }, ensure_ascii=False).encode()
                return self._send(200, body, 'application/json')
            if self.path == '/vm.json':
                vms = vms_factory(None)          # 拉模式兜底：指纹变更即重建（watcher 只广播）
                if fingerprint(vms) != state['fp']:
                    rebuild()
                body = json.dumps({'projects': state['vms'] or []}, ensure_ascii=False,
                                  separators=(',', ':')).encode('utf-8')
                return self._send(200, body, 'application/json')
            if self.path == '/events':
                return self._sse()
            if self.path.startswith('/f/'):          # V1.9.15：只读文件路由（findings/原文落点）
                return self._serve_file(self.path[3:])
            if self.path not in ('/', '/index.html'):
                return self._send(404, b'not found', 'text/plain')
            # mtime 缓存：指纹变了才重扫（刷新 <2s 的关键）
            vms = vms_factory(None)
            if fingerprint(vms) != state['fp']:
                t0 = time.time()
                rebuild()
                _broadcast({'projects': [v['meta']['project_root'] for v in vms],
                            'reason': 'serve 指纹变更（拉模式兜底通道）', 'ts': round(time.time(), 3)})
            self._send(200, state['html'].encode('utf-8'), 'text/html; charset=utf-8')
            state['last_http'] = time.time()

    def _broadcast(ev):
        for q in list(state['sse_clients']):
            try:
                q.put_nowait(ev)
            except Exception:
                state['sse_clients'].discard(q)
        if event_bus:
            try:
                event_bus(ev)
            except Exception:
                pass

    if watcher is not None:
        watcher.bus = _broadcast            # watcher 事件 → SSE 广播（推失效不推数据）
        watcher.start()
        print(f'[daemon] watcher 后端：{watcher.backend_name}（降级链：inotify→轮询）', flush=True)
    state['t0'] = time.time()
    httpd = ThreadingHTTPServer(('127.0.0.1', port), H)
    print(f'留痕产物终端 serve 中 → http://127.0.0.1:{port}/  （{state["projects"]} 项目 · '
          f'指纹 {state["fp"]} · 首次构建 {state["t_ms"]}ms · Ctrl+C 停止）')
    if snapshot:
        print('注意：--snapshot 与 --serve 组合时仅手动构建落快照（serve 内存渲染零写盘）')
    try:
        if not ttl:
            httpd.serve_forever()
        else:
            # TTL 主循环（V1.9.8）：timeout 轮转——无 HTTP 流量超过 ttl 即优雅停（D5：
            # 注册/探测流量经 healthz 不重置 last_http——只有 / 页面请求续命）
            while True:
                httpd.timeout = 5
                httpd.handle_request()
                if time.time() - state['last_http'] > ttl:
                    print(f'[daemon] TTL {ttl}s 空闲自停（last_http={int(state["last_http"])}）', flush=True)
                    break
    except KeyboardInterrupt:
        print('\n已停止。')
    finally:
        httpd.server_close()
        _daemon_shutdown(pid_path, state_path, 'ttl' if ttl else 'manual')


def _daemon_shutdown(pid_path, state_path, reason):
    """优雅停：清 pidfile+更新 state（approved 保留——豁免链不破坏）"""
    try:
        if pid_path and os.path.exists(pid_path):
            os.remove(pid_path)
    except OSError:
        pass
    if state_path:
        try:
            st = json.loads(open(state_path, encoding='utf-8').read())
        except (OSError, ValueError):
            st = {}
        st['last_stop_reason'] = reason
        st['stopped_at'] = time.strftime('%Y-%m-%d %H:%M:%S')
        tmp = state_path + '.tmp'
        with open(tmp, 'w', encoding='utf-8') as f:
            json.dump(st, f, ensure_ascii=False, indent=1)
        os.replace(tmp, state_path)


def main():
    ap = argparse.ArgumentParser(description='STD-HUB 留痕产物查看器（一期静态+二期服务）')
    ap.add_argument('--project', default=os.getcwd(), help='项目根（M5：逗号分隔多项目）')
    ap.add_argument('--out', default=None)
    ap.add_argument('--std', default=None, help='覆盖规范根（对账/调试用；serve 下忽略）')
    ap.add_argument('--dryrun', action='store_true')
    ap.add_argument('--snapshot', action='store_true', help='M6：构建后存快照+嵌 delta')
    ap.add_argument('--serve', action='store_true', help='M4：起 127.0.0.1 动态服务')
    ap.add_argument('--port', type=int, default=8777)
    a = ap.parse_args()

    projects = [x for x in a.project.split(',') if x.strip()]

    if a.serve:
        base = os.path.abspath(projects[0]) if projects else os.getcwd()

        def factory(std_override):
            projs = [base] if len(projects) == 1 else projects
            vms = collect_all(projs, None)  # serve 下走两级探测（不用 --std，装哪看哪）
            if a.snapshot:
                for vm in vms:
                    apply_delta(vm)
            return vms

        return serve(factory, a.port, a.snapshot) or 0

    std_override = a.std and os.path.abspath(a.std)
    vms = collect_all(projects, std_override)
    if not vms:
        print('无有效项目根（未扫描到任何产物目录也可构建空视图）—— 请检查 --project')
        return 2

    if a.dryrun:
        print(json.dumps({'schema_version': SCHEMA_VERSION,
                         'projects': [{'root': vm['meta']['project_root'],
                                       'two_level': vm['meta']['two_level'],
                                       'stats': vm['stats'], 'errors': vm['errors']} for vm in vms]},
                        ensure_ascii=False, indent=1))
        return 0

    if a.snapshot:
        for vm in vms:
            apply_delta(vm)  # 先嵌 delta 再渲染（对比的是渲染前的最近旧快照）

    # 产物路径：单项目→{proj}/.viewer/；多项目→首个项目 .viewer/（--out 可覆盖）
    out = a.out or os.path.join(vms[0]['meta']['project_root'], '.viewer', 'index.html')
    if out and os.path.commonpath([out, os.path.join(vms[0]['meta']['project_root'], '.standards')]) == \
            os.path.join(vms[0]['meta']['project_root'], '.standards'):
        print('S2 违例：产物不得写入 .standards/（只读白名单仅 --out）')
        return 2
    os.makedirs(os.path.dirname(out), exist_ok=True)
    html = render_html(vms, out_dir=os.path.dirname(out))
    with open(out, 'w', encoding='utf-8') as f:
        f.write(html)
    from selfcheck import selfcheck
    ok, msgs = selfcheck(out, vms[0]['meta']['project_root'])
    if not ok:
        os.remove(out)
        print('S1/S3 自检 FAIL，产物已销毁：')
        for m in msgs:
            print(' -', m)
        return 2
    if a.snapshot:
        save_snapshot(vms, out)
        print(f'快照已存 → {snap_dir(vms[0]["meta"]["project_root"])}（保留 {SNAP_KEEP} 份）')
    d0 = vms[0]['meta'].get('delta')
    dtxt = f' · Δ相对 {d0["since"]}（{"+" if d0["artifacts"]>=0 else ""}{d0["artifacts"]} 产物）' if d0 else ''
    print(f'构建完成 → {out}  （{len(vms)} 项目 · {vms[0]["stats"]["artifacts"]} 产物{dtxt} · 自检过）')
    return 0


if __name__ == '__main__':
    sys.exit(main())
