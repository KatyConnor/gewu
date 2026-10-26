#!/usr/bin/env python3
"""STD-HUB-VIEW-002 Phase 2 · 文件 watcher（推失效不推数据·技术方案 §5.1）
三后端+降级链：inotify（Linux·ctypes）→ ReadDirectoryChangesW/kqueue（接口预留）
              → 轮询指纹（任何平台兜底·watcher 失败仅失去亚秒推送功能无损）
事件归一化：路径变更 → 项目根反查 → invalidate(projects, reason) → bus 广播（去抖 300ms）
stdlib-only：ctypes/threading/queue/os/time —— 零三方依赖（S4 白名单内）
"""
import ctypes
import os
import threading
import time

# inotify 常量（Linux）
IN_CREATE = 0x00000100
IN_CLOSE_WRITE = 0x00000008
IN_DELETE = 0x00000200
IN_MOVED_TO = 0x00000080
IN_MOVED_FROM = 0x00000040
IN_ISDIR = 0x40000000
IN_MODIFY = 0x00000002
WATCH_MASK = IN_CREATE | IN_CLOSE_WRITE | IN_DELETE | IN_MODIFY | IN_MOVED_TO | IN_MOVED_FROM


def _probedirs_of(projects):
    """每项目的监控面（PROBE_DIRS 同 build.py·不含 .standards 静态面）"""
    dirs = ['audit/traces', 'audit/checkpoints', 'audit/reviews', '.lpu', '.lr', '.ops',
            'tests/qa', '.aegis']
    out = []
    for p in projects:
        for d in dirs:
            fp = os.path.join(p, d)
            if os.path.isdir(fp):
                out.append(fp)
    return out


def _project_of(path, projects):
    for p in projects:
        if path == p or path.startswith(p + os.sep):
            return p
    return None


class Watcher(threading.Thread):
    """守护线程：后端探测→事件→去抖→bus 广播。
    bus = callable(event_dict)；event = {'projects': [...], 'reason': str, 'ts': float}
    崩溃自愈：任一轮异常→降级轮询（功能无损）；再异常→静默退（页面拉模式仍在）。"""

    def __init__(self, projects_provider, bus, debounce=0.3, poll_interval=2.0,
                 force_polling=False):
        super().__init__(daemon=True, name='std-hub-watcher')
        self.projects_provider = projects_provider  # 可重入：每轮重取（注册表新增项目自动纳入）
        self.bus = bus
        self.debounce = debounce
        self.poll_interval = poll_interval
        self.force_polling = force_polling  # 测试/降级注入：跳过 inotify 直走轮询
        self.backend_name = '?'
        self.stop_flag = threading.Event()

    # ── 去抖合并 ──
    def _flush(self, hits):
        """hits = [(project_root, reason_str)] → 合并去抖后广播一次"""
        if not hits:
            return
        merged = {}
        for p, r in hits:
            merged.setdefault(p, set()).add(r)
        ev = {'projects': sorted(merged),
              'reason': '；'.join(sorted({r for rs in merged.values() for r in rs}))[:120],
              'ts': round(time.time(), 3)}
        try:
            self.bus(ev)
        except Exception:
            pass

    # ── 主循环 ──
    def run(self):
        while not self.stop_flag.is_set():
            try:
                projects = [p for p in self.projects_provider() if os.path.isdir(p)]
                if _inotify_available() and not self.force_polling:
                    self.backend_name = 'inotify'
                    self._run_inotify(projects)
                else:
                    self.backend_name = 'polling'
                    self._run_polling(projects)
            except Exception:
                time.sleep(self.poll_interval)

    # ── 轮询兜底（任何平台·降级链终点） ──
    def _run_polling(self, projects):
        last = self._stat_sig(projects)
        while not self.stop_flag.is_set():
            time.sleep(self.poll_interval)
            projects = [p for p in self.projects_provider() if os.path.isdir(p)]
            cur = self._stat_sig(projects)
            if cur != last:
                hits = []
                for p in projects:
                    if cur.get(p) != last.get(p):
                        hits.append((p, '产物目录变更'))
                self._flush(hits)
                last = cur

    def _stat_sig(self, projects):
        """轮询指纹：目录 stat + 一层文件 stat（追加不改目录 mtime——必须含文件级）"""
        sig = {}
        for d in _probedirs_of(projects):
            p = _project_of(d, projects)
            if not p:
                continue
            try:
                st = os.stat(d)
                sig.setdefault(p, []).append((d, st.st_mtime, st.st_size))
                for fn in sorted(os.listdir(d))[:2000]:
                    fp = os.path.join(d, fn)
                    try:
                        fs = os.stat(fp)
                        sig[p].append((fp, fs.st_mtime, fs.st_size))
                    except OSError:
                        pass
            except OSError:
                pass
        return {k: sorted(v) for k, v in sig.items()}

    # ── inotify 后端（Linux·ctypes） ──
    def _run_inotify(self, projects):
        import select
        import struct
        libc = ctypes.CDLL(None, use_errno=True)
        fd = libc.inotify_init1(0o4000)  # IN_NONBLOCK 阻塞式读取（线程专属）
        if fd < 0:
            raise OSError('inotify_init1 失败——降级轮询')
        wd2dir = {}
        try:
            def add_tree(root):
                for base, dirs, files in os.walk(root):
                    wd = libc.inotify_add_watch(fd, base.encode(), WATCH_MASK)
                    if wd > 0:
                        wd2dir[wd] = base
                    dirs[:] = [d for d in dirs if not d.startswith('.')]

            for d in _probedirs_of(projects):
                add_tree(d)
            buf = b''
            while not self.stop_flag.is_set():
                r, _, _ = select.select([fd], [], [], 1.0)
                if not r:
                    continue
                chunk = os.read(fd, 65536)
                if not chunk:
                    continue
                buf += chunk
                hits = []
                while len(buf) >= 16:
                    wd, mask, cookie, ln = struct.unpack('iIII', buf[:16])
                    if len(buf) < 16 + ln:
                        break
                    name = buf[16:16 + ln].rstrip(b'\x00').decode('utf-8', 'replace')
                    buf = buf[16 + ln:]
                    d = wd2dir.get(wd)
                    if d is None:
                        continue
                    if mask & IN_ISDIR and mask & IN_CREATE:
                        newdir = os.path.join(d, name)
                        w = libc.inotify_add_watch(fd, newdir.encode(), WATCH_MASK)
                        if w > 0:
                            wd2dir[w] = newdir
                    fp = os.path.join(d, name) if name else d
                    proj = _project_of(fp, projects)
                    if proj and (mask & (IN_CLOSE_WRITE | IN_CREATE | IN_DELETE | IN_MODIFY | IN_MOVED_TO | IN_MOVED_FROM)):
                        reason = ('删除' if mask & IN_DELETE else
                                  '移动' if mask & (IN_MOVED_TO | IN_MOVED_FROM) else
                                  '写入' if mask & (IN_CLOSE_WRITE | IN_MODIFY) else '新建')
                        hits.append((proj, f'{os.path.basename(d)}/{name} {reason}'))
                if hits:
                    time.sleep(self.debounce)  # 去抖窗口合并
                    self._flush(hits)
        finally:
            try:
                os.close(fd)
            except OSError:
                pass

    def stop(self):
        self.stop_flag.set()


_inotify_ok = None


def _inotify_available():
    """平台探测：Linux 且 libc 暴露 inotify_init1；win/mac 返回 False → 降级轮询
    （W3/W4 接口位：ReadDirectoryChangesW/kqueue 分支在此扩展——真机实测后启用）"""
    global _inotify_ok
    if _inotify_ok is None:
        _inotify_ok = False
        if os.name == 'posix':
            import sys
            if sys.platform.startswith('linux'):
                try:
                    libc = ctypes.CDLL(None)
                    _inotify_ok = hasattr(libc, 'inotify_init1')
                except OSError:
                    pass
    return _inotify_ok
