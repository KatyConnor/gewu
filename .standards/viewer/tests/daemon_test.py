#!/usr/bin/env python3
"""STD-HUB-VIEW-002 Phase 1 注入式测试集（实施方案计划 T1-T8 验收·先测后码铁律）
运行：HOME=<隔离目录> python3 daemon_test.py   （自动建 fakehome·不污染真实 ~/.std-hub）
覆盖：幂等/GATE 三分支/单例/TTL 自停/僵尸恢复/免重启注册/失联灰态/prune/只读纪律
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
CTL = os.path.join(HERE, '..', 'daemon_ctl.py')
PORT = 18777  # 测试专用端口，避开默认 8777

FAKE = tempfile.mkdtemp(prefix='std-hub-daemon-test-')
os.environ['HOME'] = FAKE  # expanduser('~') 全部落隔离目录
REG = os.path.join(FAKE, '.std-hub', 'registry')

WS = os.path.join(FAKE, 'ws')
PROJ_A = os.path.join(WS, 'a')
PROJ_B = os.path.join(WS, 'b')
LOST = os.path.join(FAKE, 'gone')


def sh(*args, **kw):
    return subprocess.run([sys.executable, CTL, *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True,
                          timeout=60, **kw)


def healthz():
    try:
        with urllib.request.urlopen(f'http://127.0.0.1:{PORT}/healthz', timeout=1) as r:
            return json.loads(r.read())
    except Exception:
        return None


def state():
    p = os.path.join(REG, 'daemon.state')
    return json.loads(open(p, encoding='utf-8').read()) if os.path.exists(p) else {}


def up(*extra):
    r = sh('start', '--port', str(PORT), '--approve', *extra)
    for _ in range(30):
        if healthz():
            return r
        time.sleep(0.2)
    return r


def down():
    sh('stop', '--port', str(PORT))
    for _ in range(20):
        if not healthz():
            return
        time.sleep(0.2)


class DaemonTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        os.makedirs(os.path.join(PROJ_A, 'audit', 'traces'), exist_ok=True)
        os.makedirs(os.path.join(PROJ_B, '.lpu', 'findings'), exist_ok=True)
        open(os.path.join(PROJ_B, '.lpu', 'findings', 'RECON.md'), 'w', encoding='utf-8').write(
            '# B 侦察\n- 内容\n')
        open(os.path.join(PROJ_A, 'audit', 'traces', 'a.jsonl'), 'w', encoding='utf-8').write(
            '{"ts":"10:00","type":"TASK_OPEN","summary":"A 项目"}\n')

    def setUp(self):
        down()

    @classmethod
    def tearDownClass(cls):
        down()
        shutil.rmtree(FAKE, ignore_errors=True)

    # ── T1 幂等：三次 register = 1 行 ──
    def test_01_register_idempotent(self):
        for _ in range(3):
            sh('register', PROJ_A)
        rows = [l for l in open(os.path.join(REG, 'projects.list'), encoding='utf-8')
                .read().splitlines() if l.strip() and not l.startswith('#')]
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0], PROJ_A)

    # ── T4 GATE 无标记：不拉起+输出 GATE_REQUEST ──
    def test_02_gate_no_approved(self):
        if os.path.exists(os.path.join(REG, 'daemon.state')):
            os.remove(os.path.join(REG, 'daemon.state'))
        r = sh('start', '--port', str(PORT))
        self.assertEqual(r.returncode, 3, msg=f'应输出 GATE_REQUEST 并退出 3：{r.stdout}{r.stderr}')
        self.assertIn('GATE_REQUEST', r.stdout)
        self.assertIn('回滚', r.stdout)  # 四要素申报
        time.sleep(0.5)
        self.assertIsNone(healthz(), '未批准不得拉起进程')

    # ── T4 GATE 批准：--approve → 拉起+标记 ──
    def test_03_gate_approve(self):
        if os.path.exists(os.path.join(REG, 'daemon.state')):
            os.remove(os.path.join(REG, 'daemon.state'))
        up()
        h = healthz()
        self.assertIsNotNone(h, '批准后应拉起')
        self.assertEqual(h['port'], PORT)
        self.assertTrue(state().get('approved'), '批准标记应持久化')
        down()

    # ── T4 GATE 豁免：approved 标记后再次 start 直接拉起（不重问） ──
    def test_04_gate_exempt(self):
        self.test_03_gate_approve()
        down()
        r = sh('start', '--port', str(PORT))
        self.assertEqual(r.returncode, 0)
        self.assertNotIn('GATE_REQUEST', r.stdout)
        self.assertIsNotNone(healthz())
        down()

    # ── T5 单例：并发第二实例被锁拒 ──
    def test_05_singleton(self):
        up()
        pid1 = healthz()['pid']
        for _ in range(3):
            r = sh('start', '--port', str(PORT))
            self.assertIn(r.returncode, (0, 4))
        time.sleep(0.5)
        self.assertEqual(healthz()['pid'], pid1, '第二实例不得顶替 pid')
        down()

    # ── T6 TTL：注入 3s → 无请求自停+pid 清+标记保留 ──
    def test_06_ttl_self_stop(self):
        if os.path.exists(os.path.join(REG, 'daemon.state')):
            os.remove(os.path.join(REG, 'daemon.state'))
        env = dict(os.environ, STD_HUB_VIEWER_TTL='3')
        subprocess.run([sys.executable, CTL, 'start', '--port', str(PORT), '--approve'],
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, timeout=30)
        for _ in range(15):
            if healthz():
                break
            time.sleep(0.2)
        self.assertIsNotNone(healthz(), 'TTL 短也应先起')
        t0 = time.time()
        while time.time() - t0 < 10:
            if not healthz():
                break
            time.sleep(0.3)
        self.assertIsNone(healthz(), 'TTL 3s 无流量应自停')
        self.assertFalse(os.path.exists(os.path.join(REG, 'daemon.pid')), '自停后 pid 应清')
        self.assertEqual(state().get('last_stop_reason'), 'ttl')
        self.assertTrue(state().get('approved'), 'TTL 停止保留批准标记（豁免链不破坏）')

    # ── T3 僵尸：kill -9 → status 识别 ZOMBIE → 清理后可重启 ──
    def test_07_zombie_recover(self):
        up()
        pid = healthz()['pid']
        os.kill(pid, 9)
        time.sleep(0.6)
        r = sh('status', '--port', str(PORT))
        self.assertIn('ZOMBIE', r.stdout, 'kill -9 后应识别僵尸态')
        r2 = sh('start', '--port', str(PORT))  # 豁免+僵尸清理路径
        self.assertEqual(r2.returncode, 0)
        h = healthz()
        self.assertIsNotNone(h, '僵尸清理后应可重启')
        self.assertNotEqual(h['pid'], pid)
        down()

    # ── T8 免重启注册：UP 中 register 新项目 → 页面自动出现 ──
    def test_08_registry_hot_join(self):
        up()
        sh('register', PROJ_A)
        time.sleep(0.2)
        with urllib.request.urlopen(f'http://127.0.0.1:{PORT}/', timeout=5) as r:
            html = r.read().decode()
        self.assertIn('A 项目', html, '注册后刷新即现（免重启）')
        # 运行中再注册第二个项目
        sh('register', PROJ_B)
        time.sleep(0.2)
        with urllib.request.urlopen(f'http://127.0.0.1:{PORT}/', timeout=5) as r:
            html2 = r.read().decode()
        self.assertIn('RECON', html2, '新注册项目免重启出现')
        self.assertEqual(healthz()['projects'], 2)
        down()

    # ── T8 失联灰态：注册不存在路径 → 页面失联徽（不崩） ──
    def test_09_lost_grey(self):
        up()
        sh('register', LOST)
        time.sleep(0.2)
        with urllib.request.urlopen(f'http://127.0.0.1:{PORT}/', timeout=5) as r:
            html = r.read().decode()
        self.assertIn('失联', html, '失联路径应灰态呈现而非消失/崩溃')
        down()

    # ── T1 prune：dry 报告+commit 清失联行 ──
    def test_10_prune(self):
        sh('register', LOST)
        r = sh('prune')
        self.assertIn(LOST, r.stdout)
        rows = open(os.path.join(REG, 'projects.list'), encoding='utf-8').read()
        self.assertIn(LOST, rows, 'dry 不动行')
        sh('prune', '--commit')
        rows = open(os.path.join(REG, 'projects.list'), encoding='utf-8').read()
        self.assertNotIn(LOST, rows, 'commit 后失联行删除')

    # ── S2 只读纪律：daemon 全周期不写项目目录 ──
    def test_11_readonly(self):
        import hashlib
        def tree(root):
            h = hashlib.sha256()
            for base, dirs, files in os.walk(root):
                for fn in sorted(files):
                    h.update(os.path.join(base, fn).encode())
                    h.update(open(os.path.join(base, fn), 'rb').read(100000))
            return h.hexdigest()
        t0 = tree(PROJ_A)
        up()
        sh('register', PROJ_A)
        with urllib.request.urlopen(f'http://127.0.0.1:{PORT}/', timeout=5) as r:
            r.read()
        down()
        self.assertEqual(tree(PROJ_A), t0, 'daemon 全周期不得写项目目录')


if __name__ == '__main__':
    unittest.main(verbosity=2)
