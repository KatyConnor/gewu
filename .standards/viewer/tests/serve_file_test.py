#!/usr/bin/env python3
"""STD-HUB-VIEW-001 V1.9.15 只读文件服务回归测试（/f 路由·findings/原文链接落点）
运行：python3 serve_file_test.py（HOME 隔离+PORT 18779·复用 daemon_ctl GATE --approve 快速拉起）
覆盖：①链接生成（/f/<idx>/<rel>）②白名单前缀 ③路径穿越拦截 ④S1 同源脱敏
      ⑤越界项目序号 ⑥非法路由格式 ⑦会话原文路由
起因：用户实测报障——serve 模式点击 findings 文件 404（../ 相对链在 serve 下无落点）
"""
import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.request
import urllib.error

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..'))   # build 可导入
CTL = os.path.join(HERE, '..', 'daemon_ctl.py')
PORT = 18779

FAKE = tempfile.mkdtemp(prefix='std-hub-file-test-')
os.environ['HOME'] = FAKE
os.environ['STD_HUB_VIEWER_PORT'] = str(PORT)
REG = os.path.join(FAKE, '.std-hub', 'registry')
PROJ = os.path.join(FAKE, 'p1')
FIND = os.path.join(PROJ, '.lpu', 'findings')
TR = os.path.join(PROJ, 'audit', 'traces')


def sh(*args):
    return subprocess.run([sys.executable, CTL, *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, timeout=60)


def up():
    sh('start', '--approve')
    for _ in range(40):
        try:
            with urllib.request.urlopen(f'http://127.0.0.1:{PORT}/healthz', timeout=1) as r:
                if json.load(r).get('ok'):
                    return
        except Exception:
            time.sleep(0.25)
    raise RuntimeError('daemon 未起')


def down():
    sh('stop')


def get(path):
    try:
        with urllib.request.urlopen(f'http://127.0.0.1:{PORT}{path}', timeout=5) as r:
            return r.status, r.read().decode('utf-8', errors='replace')
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', errors='replace')


class ServeFileTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        os.makedirs(FIND, exist_ok=True)
        os.makedirs(TR, exist_ok=True)
        with open(os.path.join(FIND, 'D-data.md'), 'w', encoding='utf-8') as f:
            f.write('# D 数据模型\n\n- 用户表 user(id, name)\n- 订单表 order(id, uid)\n')
        with open(os.path.join(TR, 's-20261011-090000.jsonl'), 'w', encoding='utf-8') as f:
            f.write('{"ts":"09:00","type":"TASK_OPEN","summary":"文件路由测试"}\n')
            f.write('{"ts":"09:01","type":"STEP_DONE","summary":"写 findings"}\n')

    def setUp(self):
        down()
        sh('register', PROJ)
        up()

    def tearDown(self):
        down()

    def test_01_link_generation_serve_mode(self):
        """① serve 模式数据岛含 file_serve 路由与 FURL 链接函数（链接为 JS 运行时渲染）"""
        code, html = get('/')
        self.assertEqual(code, 200)
        self.assertIn('"file_serve":"/f"', html, '数据岛应声明 /f 文件路由（serve 模式）')
        self.assertIn('const FURL', html, '模板应含模式感知链接函数')
        self.assertNotIn('href="../', html, '模板静态层不得残留 ../ 相对链接')

    def test_02_whitelist_serve_ok(self):
        """② 白名单内文件可读：findings（.lpu/）"""
        code, body = get('/f/0/.lpu/findings/D-data.md')
        self.assertEqual(code, 200)
        self.assertIn('用户表 user', body)

    def test_03_whitelist_reject(self):
        """③ 白名单外拒服务：.standards/ 与根级 README"""
        code, _ = get('/f/0/.standards/suite_check.py')
        self.assertEqual(code, 403, '.standards 不在白名单')
        code, _ = get('/f/0/README.md')
        self.assertEqual(code, 403, '根级 README 不在白名单')

    def test_04_traversal_blocked(self):
        """④ 路径穿越拦截（../ 编码态与裸态）"""
        for p in ('/f/0/%2e%2e/%2e%2e/etc/passwd',
                  '/f/0/../../../etc/passwd',
                  '/f/0/.lpu/../../../../etc/passwd'):
            code, body = get(p)
            self.assertIn(code, (403, 404), f'{p} 必须被拦')
            self.assertNotIn('root:', body, '不得泄出系统文件')

    def test_05_mask_credentials(self):
        """⑤ S1 同源脱敏：trace 文件含凭据 → 出网内容已打码"""
        with open(os.path.join(TR, 's-20261011-090100.jsonl'), 'w', encoding='utf-8') as f:
            f.write('{"ts":"09:02","type":"NOTE","summary":"password: Sup3rS3cretValue 登录"}\n')
        get('/')  # 触发重扫
        time.sleep(0.3)
        code, body = get('/f/0/audit/traces/s-20261011-090100.jsonl')
        self.assertEqual(code, 200)
        self.assertNotIn('Sup3rS3cretValue', body, '凭据必须被打码')
        self.assertIn('***', body, '打码标记在场')

    def test_06_bad_project_index(self):
        """⑥ 越界项目序号 → 404"""
        code, _ = get('/f/9/.lpu/findings/D-data.md')
        self.assertEqual(code, 404)

    def test_07_bad_route_format(self):
        """⑦ 非法路由格式（无序号/空路径）→ 404"""
        code, _ = get('/f/x/.lpu/findings/D-data.md')
        self.assertEqual(code, 404)
        code, _ = get('/f/0/')
        self.assertEqual(code, 404)

    def test_08_session_raw_link(self):
        """⑧ 会话原文链接走 /f 路由（audit/traces/ 在白名单）"""
        code, body = get('/f/0/audit/traces/s-20261011-090000.jsonl')
        self.assertEqual(code, 200)
        self.assertIn('TASK_OPEN', body)


if __name__ == '__main__':
    unittest.main(verbosity=2)
