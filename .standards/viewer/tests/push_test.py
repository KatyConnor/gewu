#!/usr/bin/env python3
"""STD-HUB-VIEW-002 Phase 2 推送注入测试（技术方案 §5.4 五项验收·先测后码）
运行：python3 push_test.py（HOME 隔离+PORT 18778·复用 daemon_ctl GATE --approve 快速拉起）
覆盖：①SSE invalidate 亚秒推送 ②inotify 事件归一化 ③降级链（watcher 挂→轮询仍推）
      ④SSE 断连重连续流 ⑤推送通道纯失效零产物内容
"""
import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..'))   # watcher/build 可导入
CTL = os.path.join(HERE, '..', 'daemon_ctl.py')
PORT = 18778

FAKE = tempfile.mkdtemp(prefix='std-hub-push-test-')
os.environ['HOME'] = FAKE
os.environ['STD_HUB_VIEWER_PORT'] = str(PORT)
REG = os.path.join(FAKE, '.std-hub', 'registry')
PROJ = os.path.join(FAKE, 'p1')
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


class PushTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        os.makedirs(TR, exist_ok=True)
        with open(os.path.join(TR, 's.jsonl'), 'w', encoding='utf-8') as f:
            f.write('{"ts":"10:00","type":"TASK_OPEN","summary":"push 项目"}\n')

    def setUp(self):
        down()
        sh('register', PROJ)
        up()

    def tearDown(self):
        down()

    def _sse_events(self, secs=4.0):
        """裸 socket 收 SSE 流（不依赖 EventSource 客户端）"""
        import socket
        s = socket.create_connection(('127.0.0.1', PORT), timeout=5)
        s.sendall(f'GET /events HTTP/1.1\r\nHost: 127.0.0.1\r\nAccept: text/event-stream\r\n\r\n'.encode())
        s.settimeout(secs)
        buf = b''
        t0 = time.time()
        while time.time() - t0 < secs:
            try:
                chunk = s.recv(4096)
            except socket.timeout:
                break
            if not chunk:
                break
            buf += chunk
        s.close()
        text = buf.decode('utf-8', errors='replace')
        evs = [ln[6:] for ln in text.splitlines() if ln.startswith('data: ')]
        return text, evs

    def test_01_sse_invalidate_subsecond(self):
        """① 追加事件 → 1s 内 SSE 收 invalidate + vm.json 更新"""
        import threading
        box = {}
        def run():
            box['text'], box['evs'] = self._sse_events(4.0)
        th = threading.Thread(target=run)
        th.start()
        time.sleep(0.8)  # 等 SSE 建连
        with open(os.path.join(TR, 's.jsonl'), 'a', encoding='utf-8') as f:
            f.write('{"ts":"10:05","type":"STEP_DONE","summary":"新步骤"}\n')
        th.join()
        self.assertTrue(box['evs'], '事件队列非空（推送到达）')
        ev = json.loads(box['evs'][-1])
        self.assertIn(PROJ, ev.get('projects', []), '失效事件含项目根')
        with urllib.request.urlopen(f'http://127.0.0.1:{PORT}/vm.json', timeout=5) as r:
            vm = json.load(r)
        evs = sum(p.get('stats', {}).get('events', 0) for p in vm['projects'])
        self.assertGreaterEqual(evs, 2, 'vm.json 已含新事件（refresh 数据源）')

    def test_02_inotify_event_normalize(self):
        """② inotify 归一化：创建/写入/删除三类事件都推 invalidate"""
        from watcher import Watcher
        out = []
        w = Watcher(lambda: [PROJ], bus=lambda ev: out.append(ev), debounce=0.2)
        w.start()
        time.sleep(0.5)
        fp = os.path.join(TR, 'x.jsonl')
        open(fp, 'w').write('{"ts":"11:00","type":"TASK_OPEN","summary":"c"}\n')  # create+close_write
        time.sleep(0.6)
        open(fp, 'a').write('{"ts":"11:01","type":"STEP_DONE","summary":"w"}\n')  # modify
        time.sleep(0.6)
        os.remove(fp)  # delete
        time.sleep(0.8)
        w.stop()
        reasons = ' | '.join(e.get('reason', '') for e in out)
        self.assertTrue(out, 'watcher 事件非空')
        self.assertIn('新建', reasons)
        self.assertIn('写入', reasons)
        self.assertEqual('inotify', w.backend_name, 'Linux 主后端')

    def test_03_fallback_polling(self):
        """③ 降级链：强制轮询后端（模拟 inotify 不可用）→ 仍推 invalidate"""
        from watcher import Watcher
        out = []
        w = Watcher(lambda: [PROJ], bus=lambda ev: out.append(ev), debounce=0.1,
                    force_polling=True)
        w.start()
        time.sleep(1.2)  # 首轮基线
        with open(os.path.join(TR, 's.jsonl'), 'a', encoding='utf-8') as f:
            f.write('{"ts":"12:00","type":"DECISION","summary":"d"}\n')
        time.sleep(3.2)  # 轮询间隔 2s
        w.stop()
        self.assertTrue(out, '轮询兜底也推送失效')
        self.assertEqual('polling', w.backend_name)

    def test_04_sse_reconnect(self):
        """④ SSE 断连重连：新连接照常收流（服务存活）"""
        self._sse_events(1.5)          # 建连即断
        text2, evs2 = self._sse_events(2.0)
        self.assertIn('text/event-stream', text2, '重连成功且响应类型正确')
        with urllib.request.urlopen(f'http://127.0.0.1:{PORT}/healthz', timeout=3) as r:
            self.assertTrue(json.load(r)['ok'], '服务在断连后存活')

    def test_05_channel_content_free(self):
        """⑤ 抓包审计：推送通道纯 invalidate 元数据——零产物内容（summary 不出现）"""
        import threading
        box = {}
        def run():
            _, box['evs'] = self._sse_events(4.0)
        th = threading.Thread(target=run)
        th.start()
        time.sleep(0.8)
        with open(os.path.join(TR, 's.jsonl'), 'a', encoding='utf-8') as f:
            f.write('{"ts":"13:00","type":"RISK_PRE","summary":"机密内容SENTINEL-9931"}\n')
        th.join()
        joined = ''.join(box['evs'] or [])
        self.assertNotIn('SENTINEL-9931', joined, '推送通道不得携带产物内容')
        for ev_str in (box['evs'] or []):
            ev = json.loads(ev_str)
            self.assertTrue(set(ev.keys()) <= {'projects', 'reason', 'ts'},
                            '事件字段白名单：projects/reason/ts')


if __name__ == '__main__':
    unittest.main(verbosity=2)
