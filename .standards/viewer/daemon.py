#!/usr/bin/env python3
"""STD-HUB-VIEW-002 Phase 1 · 数据面（daemon.py）——常驻监控预览 daemon
由 daemon_ctl.py start 拉起（GATE 后·setsid 脱离会话）：
  单例锁（flock/msvcrt/TCP 兜底）→ pidfile → 注册表驱动的 serve 工厂 → TTL 主循环
技术方案 §3.2/§3.4/§3.5 · 全链只读（S1-S5 不变·serve 模式零写盘）
"""
import fcntl
import json
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

REG = os.path.expanduser('~/.std-hub/registry')
PIDF = os.path.join(REG, 'daemon.pid')
STATE = os.path.join(REG, 'daemon.state')


def _write_pid():
    os.makedirs(REG, exist_ok=True)
    with open(PIDF, 'w', encoding='utf-8') as f:
        f.write(str(os.getpid()))


def _update_state(**kv):
    try:
        st = json.loads(open(STATE, encoding='utf-8').read())
    except (OSError, ValueError):
        st = {}
    st.update(kv)
    tmp = STATE + '.tmp'
    with open(tmp, 'w', encoding='utf-8') as f:
        json.dump(st, f, ensure_ascii=False, indent=1)
    os.replace(tmp, STATE)


def _acquire_lock():
    """单例锁：fcntl（Linux/macOS）→ msvcrt（Windows）→ pidfile 语义兜底（§3.4）"""
    lock = os.path.join(REG, 'daemon.lock')
    os.makedirs(REG, exist_ok=True)
    lf = open(lock, 'w')
    try:
        import fcntl as _f
        _f.flock(lf.fileno(), _f.LOCK_EX | _f.LOCK_NB)
        return lf
    except ImportError:
        try:
            import msvcrt
            msvcrt.locking(lf.fileno(), msvcrt.LK_NBLCK, 1)
            return lf
        except (ImportError, OSError):
            pass  # 双平台均无锁原语 → 退化为 pidfile+healthz 语义兜底（ctl 侧已拒）
    except OSError:
        print('单例锁被占——已有 daemon 实例', file=sys.stderr)
        sys.exit(4)
    return lf


def main():
    lf = _acquire_lock()
    port = int(os.environ.get('STD_HUB_VIEWER_PORT', '8777'))
    ttl = int(os.environ.get('STD_HUB_VIEWER_TTL', '14400'))
    _write_pid()
    _update_state(pid=os.getpid(), port=port, started_at=time.strftime('%Y-%m-%d %H:%M:%S'),
                  pid_alive_since=os.getpid())
    from build import serve, registry_factory
    from watcher import Watcher
    print(f'[daemon] pid={os.getpid()} port={port} ttl={ttl}s '
          f'registry={os.path.join(REG, "projects.list")}', flush=True)

    def _projects_provider():
        """可重入项目源（watcher 每轮重读注册表——新注册项目自动入 watch）"""
        try:
            return [l.strip() for l in open(os.path.join(REG, 'projects.list'),
                                            encoding='utf-8').read().splitlines()
                    if l.strip() and not l.startswith('#')]
        except OSError:
            return []

    w = Watcher(_projects_provider, bus=lambda ev: None)  # bus 由 serve 内广播接管
    serve(registry_factory, port, snapshot=False, ttl=ttl, pid_path=PIDF, state_path=STATE,
          event_bus=None, watcher=w)
    print('[daemon] exit', flush=True)


if __name__ == '__main__':
    main()
