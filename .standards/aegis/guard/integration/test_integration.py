#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
test_integration —— AEGIS 集成层回归测试（零依赖 runner）

覆盖: 只读锁 / 写-检-回滚 / 冻结拦截 / 红绿基线 / 阶段门 schema /
安灯升级 / 全流程演示 / MCP 通道（进程内 + stdio 端到端）。
运行: python3 test_integration.py   （全绿退出 0）
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)                                # aegis-guard 根
for p in (HERE, ROOT):
    if p not in sys.path:
        sys.path.insert(0, p)

from aegis_agent import GuardedWorkspace, ToolError        # noqa: E402
from aegis_agent import StageController, AtlasBridge       # noqa: E402
import demo_agent as da                                    # noqa: E402
import mcp_server as mc                                    # noqa: E402

RESULTS = []


def case(name):
    def deco(fn):
        def run():
            fn()
            RESULTS.append((name, True, None))
        run.__name__ = fn.__name__
        return run
    return deco


def _mkrepo():
    repo = tempfile.mkdtemp(prefix='aegis-it-')
    for rel in ('token.py', 'tests/test_smoke.py',
                '.github/workflows/ci.yml'):
        dst = os.path.join(repo, rel)
        d = os.path.dirname(dst)
        if d:
            os.makedirs(d, exist_ok=True)
        shutil.copy(os.path.join(HERE, 'demo_repo', rel), dst)
    return repo


def _good_fp():
    fp = dict(da.FP)
    fp['budget'] = dict(fp['budget'])
    return fp


@case('只读锁: P1 写操作被拒绝')
def t_readonly():
    repo = _mkrepo()
    ws = GuardedWorkspace(repo, plan=None, level='S3', readonly=True)
    try:
        ws.write('token.py', 'x = 1')
        raise AssertionError('只读锁未生效')
    except ToolError:
        pass
    assert open(os.path.join(repo, 'token.py'), encoding='utf-8').read() \
        .startswith('"""JWT 验签入口（演示仓库）——缺陷版本')


@case('写-检-回滚: 坏修复被拦截且磁盘未变')
def t_bad_write():
    repo = _mkrepo()
    ws = GuardedWorkspace(repo, plan=_good_fp(), level='S3')
    before = open(os.path.join(repo, 'token.py'), encoding='utf-8').read()
    try:
        ws.write('token.py', da.BAD_TOKEN)
        raise AssertionError('坏修复未被拦截')
    except ToolError as e:
        ids = [v['id'] for v in e.report.get('blocks', [])]
        assert 'AP001' in ids and 'AP002' in ids, ids
    after = open(os.path.join(repo, 'token.py'), encoding='utf-8').read()
    assert after == before, '拦截后磁盘内容被改动（回滚失效）'


@case('冻结清单: CI 配置触碰被拦截')
def t_frozen():
    repo = _mkrepo()
    ws = GuardedWorkspace(repo, plan=_good_fp(), level='S3')
    try:
        ws.write('.github/workflows/ci.yml', da.BAD_CI)
        raise AssertionError('冻结文件写入未被拦截')
    except ToolError as e:
        ids = [v['id'] for v in e.report.get('blocks', [])]
        assert 'FROZEN_TOUCH' in ids and 'PLAN_DEVIATION' in ids, ids


@case('红绿基线: 修复前红、修复后绿')
def t_red_green():
    repo = _mkrepo()
    ws = GuardedWorkspace(repo, plan=_good_fp(), level='S3')
    ws.write('tests/test_alg_none.py', da.RED_TEST)          # 先写复现用例
    r_red = subprocess.run([sys.executable, 'tests/test_alg_none.py'],
                           cwd=repo, capture_output=True)
    assert r_red.returncode != 0, '缺陷版本应跑出红（基线缺失?）'
    ws.write('token.py', da.GOOD_TOKEN)
    r_green = subprocess.run([sys.executable, 'tests/test_alg_none.py'],
                             cwd=repo, capture_output=True)
    assert r_green.returncode == 0, r_green.stderr.decode()[:200]
    rep = ws.last_report()
    assert rep['verdict'] in ('PASS', 'WARN_ONLY'), rep['verdict']


@case('阶段门: schema 连续 2 次不合格触发安灯升级')
def t_stage_gate():
    repo = _mkrepo()
    bridge = AtlasBridge(os.path.join(repo, 'events.jsonl'))
    sc = StageController(da.ISSUE, 'S3', bridge=bridge)
    for _ in range(2):
        assert sc.accept_doc('RC', da.RC_PARTIAL), '坏 RC 应被拒'
    assert sc.escalated, '连续两次不合格未触发安灯'
    assert bridge.by_type('x-fix.escalate'), '升级事件未留痕'


@case('全流程演示: 收敛/拦截/红转绿/留痕')
def t_full_demo():
    s = da.run_demo(verbose=False)
    assert s['converged'] is True
    assert s['rounds'] == 2 and s['intercepts'] >= 2
    assert s['red'] is True and s['green'] is True
    assert not s.get('escalated')
    assert s['events'] >= 10
    for k in ('issue', 'root_cause', 'impact', 'evidence', 'rollback', 'diff'):
        assert k in s['pr'], 'PR 缺少强制字段 ' + k


@case('顽固模式: 拒不收敛 → 安灯升级 + critical 留痕')
def t_stubborn():
    s = da.run_demo(stubborn=True, verbose=False)
    assert s['converged'] is False and s['escalated'] is True
    assert s['rounds'] == 3


@case('MCP 进程内: initialize/list/call 双向判定')
def t_mcp_inline():
    r = mc.handle_request({'jsonrpc': '2.0', 'id': 1, 'method': 'initialize'})
    assert r['result']['serverInfo']['name'] == 'aegis-guard'
    r = mc.handle_request({'jsonrpc': '2.0', 'id': 2, 'method': 'tools/list'})
    names = [t['name'] for t in r['result']['tools']]
    assert 'aegis_check' in names and 'aegis_docs' in names
    r = mc.handle_request({'jsonrpc': '2.0', 'id': 3, 'method': 'tools/call',
                           'params': {'name': 'aegis_check', 'arguments': {
                               'diff_path': os.path.join(ROOT, 'fixtures', 'good.diff'),
                               'plan_path': os.path.join(ROOT, 'docs', 'FP-003.yaml')}}})
    rep = json.loads(r['result']['content'][0]['text'])
    assert rep['verdict'] == 'PASS' and not r['result']['isError']
    r = mc.handle_request({'jsonrpc': '2.0', 'id': 4, 'method': 'tools/call',
                           'params': {'name': 'aegis_check', 'arguments': {
                               'diff_path': os.path.join(ROOT, 'fixtures', 'bad.diff')}}})
    rep = json.loads(r['result']['content'][0]['text'])
    assert rep['verdict'] == 'BLOCK' and r['result']['isError']
    assert mc.handle_request({'jsonrpc': '2.0', 'method': 'notifications/initialized'}) is None


@case('MCP stdio 端到端: 子进程协议通道')
def t_mcp_stdio():
    proc = subprocess.Popen(
        [sys.executable, os.path.join(HERE, 'mcp_server.py')],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
    try:
        req = {'jsonrpc': '2.0', 'id': 9, 'method': 'tools/call',
               'params': {'name': 'aegis_check', 'arguments': {
                   'diff_path': os.path.join(ROOT, 'fixtures', 'bad.diff')}}}
        proc.stdin.write(json.dumps(req) + '\n')
        proc.stdin.flush()
        resp = json.loads(proc.stdout.readline())
        rep = json.loads(resp['result']['content'][0]['text'])
        assert resp['result']['isError'] and rep['verdict'] == 'BLOCK'
    finally:
        proc.stdin.close()
        proc.wait(timeout=5)


def main():
    tests = [v for k, v in sorted(globals().items())
             if k.startswith('t_') and callable(v)]
    failed = 0
    for fn in tests:
        try:
            fn()
        except Exception as e:                              # noqa: BLE001
            failed += 1
            RESULTS.append((fn.__name__, False, str(e)[:120]))
    print('=' * 64)
    for name, ok, err in RESULTS:
        mark = 'PASS' if ok else 'FAIL'
        print(' [%s] %s%s' % (mark, name, ('  ← ' + err) if err else ''))
    print('=' * 64)
    print('集成层测试: %d/%d 通过' % (len(RESULTS) - failed, len(RESULTS)))
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
