#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
mcp_client_demo —— 模拟 zcode 智能体经 MCP 自查

流程: initialize → tools/list → aegis_check(合规 diff) → aegis_check(违规 diff)
证明: MCP 通道可用，违规 diff 以 isError=True + 违例清单回流给模型。
"""
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)                                # aegis-guard 根


def main():
    proc = subprocess.Popen(
        [sys.executable, os.path.join(HERE, 'mcp_server.py')],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)

    ids = [0]

    def rpc(method, params=None):
        ids[0] += 1
        req = {'jsonrpc': '2.0', 'id': ids[0], 'method': method}
        if params:
            req['params'] = params
        proc.stdin.write(json.dumps(req) + '\n')
        proc.stdin.flush()
        return json.loads(proc.stdout.readline())

    print('[1] initialize →',
          rpc('initialize')['result']['serverInfo'])
    proc.stdin.write(json.dumps(
        {'jsonrpc': '2.0', 'method': 'notifications/initialized'}) + '\n')
    proc.stdin.flush()

    tools = rpc('tools/list')['result']['tools']
    print('[2] tools/list →', [t['name'] for t in tools])

    ok = rpc('tools/call', {'name': 'aegis_check', 'arguments': {
        'diff_path': os.path.join(ROOT, 'fixtures', 'good.diff'),
        'plan_path': os.path.join(ROOT, 'docs', 'FP-003.yaml')}})
    rep = json.loads(ok['result']['content'][0]['text'])
    print('[3] aegis_check(合规) → verdict=%s isError=%s'
          % (rep['verdict'], ok['result']['isError']))

    bad = rpc('tools/call', {'name': 'aegis_check', 'arguments': {
        'diff_path': os.path.join(ROOT, 'fixtures', 'bad.diff'),
        'plan_path': os.path.join(ROOT, 'docs', 'FP-bad.yaml')}})
    rep2 = json.loads(bad['result']['content'][0]['text'])
    print('[4] aegis_check(违规) → verdict=%s isError=%s 违例=%d 项'
          % (rep2['verdict'], bad['result']['isError'],
             rep2.get('n_violations', len(rep2.get('blocks', [])))))

    proc.stdin.close()
    proc.wait(timeout=5)

    good_case = rep['verdict'] == 'PASS' and not ok['result']['isError']
    bad_case = rep2['verdict'] == 'BLOCK' and bad['result']['isError']
    print('\n结论: 合规自查 PASS=%s, 违规自查 BLOCK=%s —— %s'
          % (good_case, bad_case,
             'MCP 通道双向可用' if good_case and bad_case else '异常'))
    return 0 if good_case and bad_case else 1


if __name__ == '__main__':
    sys.exit(main())
