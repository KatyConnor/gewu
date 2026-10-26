#!/usr/bin/env python3
"""STD-HUB-VIEW-002 Phase 1 · 控制面（daemon_ctl.py）
用法：
  python3 daemon_ctl.py status [--port N]              # UP/DOWN/ZOMBIE 三态探测
  python3 daemon_ctl.py register <项目根>              # 幂等注册进全局注册表
  python3 daemon_ctl.py start [--port N] [--approve]   # GATE 协议拉起（首次审批+豁免）
  python3 daemon_ctl.py stop  [--port N]               # 优雅停（清 pid·保留 approved）
  python3 daemon_ctl.py prune [--commit]               # 失联行报告/清理
技术方案 §3（注册表 schema/状态机/GATE/单例兜底）· 控制面与数据面分离（D3）
退出码：0=正常/已完成；3=待 GATE 审批（未拉起）；4=已有实例（单例拒）
"""
import argparse
import json
import os
import signal
import subprocess
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
REG = os.path.expanduser('~/.std-hub/registry')
LIST = os.path.join(REG, 'projects.list')
STATE = os.path.join(REG, 'daemon.state')
PIDF = os.path.join(REG, 'daemon.pid')
LOG = os.path.join(REG, 'daemon.log')


def _init():
    os.makedirs(REG, exist_ok=True)


def healthz(port):
    try:
        with urllib.request.urlopen(f'http://127.0.0.1:{port}/healthz', timeout=1) as r:
            return json.loads(r.read())
    except Exception:
        return None


def read_state():
    try:
        return json.loads(open(STATE, encoding='utf-8').read())
    except (OSError, ValueError):
        return {}  # 损坏 state 容错：重建为未批准态（技术方案 T2）


def write_state(**kv):
    _init()
    st = read_state()
    st.update(kv)
    tmp = STATE + '.tmp'
    with open(tmp, 'w', encoding='utf-8') as f:
        json.dump(st, f, ensure_ascii=False, indent=1)
    os.replace(tmp, STATE)


def pid_alive(pid):
    try:
        os.kill(pid, 0)
        return True
    except (OSError, ValueError):
        return False


def read_pid():
    try:
        return int(open(PIDF, encoding='utf-8').read().strip())
    except (OSError, ValueError):
        return None


def clear_pid():
    try:
        os.remove(PIDF)
    except OSError:
        pass


# ── 注册表（T1） ──
def register(path):
    path = os.path.normpath(os.path.abspath(path))
    _init()
    rows = rows_of()
    if path in rows:
        return f'已注册（幂等）：{path}'
    with open(LIST, 'a', encoding='utf-8') as f:
        f.write(path + '\n')
    return f'注册成功：{path}（共 {len(rows) + 1} 项目）'


def rows_of():
    if not os.path.exists(LIST):
        return []
    return [l.strip() for l in open(LIST, encoding='utf-8').read().splitlines()
            if l.strip() and not l.startswith('#')]


def prune(commit):
    lost = [r for r in rows_of() if not os.path.isdir(r)]
    if not commit:
        print(f'失联 {len(lost)} 行（dry 模式·加 --commit 清理）：')
        for r in lost:
            print(f'  {r}')
        return 0
    keep = [r for r in rows_of() if os.path.isdir(r)]
    tmp = LIST + '.tmp'
    with open(tmp, 'w', encoding='utf-8') as f:
        f.write('\n'.join(keep) + ('\n' if keep else ''))
    os.replace(tmp, LIST)
    print(f'已清理失联 {len(lost)} 行，保留 {len(keep)} 行')
    return 0


# ── 状态探测（T3） ──
def status(port):
    h = healthz(port)
    if h:
        st = read_state()
        print(json.dumps({'state': 'UP', 'pid': h.get('pid'), 'port': h.get('port'),
                          'projects': h.get('projects'), 'approved': st.get('approved'),
                          'ttl_left_s': h.get('ttl_left_s'),
                          'registry_rows': h.get('registry_rows')},
                         ensure_ascii=False))
        return 0
    pid = read_pid()
    if pid and pid_alive(pid):
        print(json.dumps({'state': 'ZOMBIE', 'pid': pid,
                          'hint': 'pid 活但 healthz 死（注册表可能漂移）——start 将清理后重启'}, ensure_ascii=False))
        return 0
    if pid:  # pidfile 残留+pid 死（kill -9 现场·优雅停未及执行）→ 僵尸残留态
        print(json.dumps({'state': 'ZOMBIE', 'pid': pid, 'kind': 'stale_pidfile',
                          'hint': 'pid 已死但 pidfile 残留——start 将清理后重启'}, ensure_ascii=False))
        return 0
    print(json.dumps({'state': 'DOWN', 'approved': read_state().get('approved', False)}, ensure_ascii=False))
    return 0


# ── GATE 协议拉起（T4/T5） ──
def start(port, approve):
    _init()
    h = healthz(port)
    if h:
        print(f'已 UP（pid {h["pid"]}·{h["projects"]} 项目）——单例拒新实例')
        return 4
    pid = read_pid()
    if pid and pid_alive(pid) and not h:
        print(f'僵尸态（pid {pid} 活但 healthz 死）→ 清理残留后拉起')
        try:
            os.kill(pid, signal.SIGKILL)
        except OSError:
            pass
        clear_pid()
    elif pid:
        clear_pid()

    st = read_state()
    if not st.get('approved') and not approve:
        # GATE_REQUEST（R2 · 技术方案 §3.3 四要素申报）——无静默拉起路径（G11-5）
        print('GATE_REQUEST（R2 · 起本地常驻进程，需人工审批）')
        print('  目标：python3 daemon.py 常驻 127.0.0.1:%d（setsid 脱离会话）' % port)
        print('  影响：跨会话存续·磁盘仅 ~/.std-hub/registry 三个小文件（list/state/pid）')
        print('  回滚：daemon_ctl.py stop 或 kill <pid> + rm daemon.state（完全可逆）')
        print('  替代：不装 daemon——继续手动 python3 build.py --serve')
        print('批准方式：daemon_ctl.py start --approve   （批准后写 approved 标记，后续幂等豁免）')
        return 3
    if approve:
        write_state(approved=True, port=port)
    _spawn(port)
    for _ in range(30):
        if healthz(port):
            print(f'拉起成功：http://127.0.0.1:{port} （approved 标记已持久化）')
            return 0
        time.sleep(0.2)
    print('拉起失败——查 %s' % LOG)
    return 1


def _spawn(port):
    env = dict(os.environ)
    env.setdefault('STD_HUB_VIEWER_PORT', str(port))
    env.setdefault('STD_HUB_VIEWER_TTL', '14400')  # 4h 默认（技术方案 §3.5）
    with open(LOG, 'a', encoding='utf-8') as lf:
        subprocess.Popen([sys.executable, os.path.join(HERE, 'daemon.py')],
                         start_new_session=True, stdout=lf, stderr=lf, env=env)


# ── 停止（T7） ──
def stop(port):
    h = healthz(port)
    if h and h.get('pid'):
        try:
            os.kill(h['pid'], signal.SIGTERM)
        except OSError:
            pass
        for _ in range(20):
            if not healthz(port):
                break
            time.sleep(0.2)
        if healthz(port):
            try:
                os.kill(h['pid'], signal.SIGKILL)
            except OSError:
                pass
        write_state(last_stop_reason='manual')
        print(f'已停止（pid {h["pid"]}·approved 标记保留）')
        return 0
    pid = read_pid()
    if pid and pid_alive(pid):
        os.kill(pid, signal.SIGKILL)
        clear_pid()
        print(f'已强停僵尸（pid {pid}）')
        return 0
    print('本就 DOWN')
    return 0


def main():
    ap = argparse.ArgumentParser(description='STD-HUB 留痕产物终端·控制面')
    ap.add_argument('cmd', choices=['status', 'register', 'start', 'stop', 'prune'])
    ap.add_argument('path', nargs='?', help='register 的项目根')
    ap.add_argument('--port', type=int, default=int(os.environ.get('STD_HUB_VIEWER_PORT', 8777)))
    ap.add_argument('--approve', action='store_true', help='批准 GATE（写 approved 标记后拉起）')
    ap.add_argument('--commit', action='store_true', help='prune 真删失联行')
    a = ap.parse_args()
    if a.cmd == 'register':
        if not a.path:
            print('用法：daemon_ctl.py register <项目根>'); return 2
        print(register(a.path)); return 0
    if a.cmd == 'prune':
        return prune(a.commit)
    if a.cmd == 'status':
        return status(a.port)
    if a.cmd == 'start':
        return start(a.port, a.approve)
    if a.cmd == 'stop':
        return stop(a.port)
    return 2


if __name__ == '__main__':
    sys.exit(main())
