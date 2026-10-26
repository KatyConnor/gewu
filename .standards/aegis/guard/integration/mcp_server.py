#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
mcp_server —— AEGIS Guard 的 MCP 服务器（stdio / ndjson JSON-RPC 2.0）

zcode 类外部智能体接法 A: 模型经 MCP 主动自查（决定「体验」）；
平台侧仍须在 CI 独立复跑（决定「纪律」）—— 自查不能替代终审。

启动: python3 mcp_server.py   （stdin/stdout 即协议通道）
工具:
  aegis_check  对修复 diff 执行护栏检查（返回结构化报告）
  aegis_docs   三单契约校验
"""
import contextlib
import io
import json
import os
import sys

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)
import aegis_guard as ag                                   # noqa: E402

PROTOCOL = '2024-11-05'

TOOLS = [
    {
        'name': 'aegis_check',
        'description': '对修复 diff 执行 AEGIS V1.0 护栏检查: '
                       '冻结清单/测试篡改/禁改模式/diff 预算/三单契约/计划比对',
        'inputSchema': {
            'type': 'object',
            'properties': {
                'diff_path': {'type': 'string',
                              'description': 'unified diff 文件路径（git diff 输出）'},
                'plan_path': {'type': 'string', 'description': '方案单 FP.yaml 路径'},
                'level': {'type': 'string', 'enum': ['S0', 'S1', 'S2', 'S3', 'S4']},
            },
            'required': ['diff_path'],
        },
    },
    {
        'name': 'aegis_docs',
        'description': '三单契约校验（RC/FP/VR 任意子集）',
        'inputSchema': {
            'type': 'object',
            'properties': {
                'rc': {'type': 'string', 'description': '根因单 RC.yaml 路径'},
                'fp': {'type': 'string', 'description': '方案单 FP.yaml 路径'},
                'vr': {'type': 'string', 'description': '验证单 VR.yaml 路径'},
            },
        },
    },
]


def _load_yaml(path):
    if not path or not os.path.exists(path):
        return None
    with open(path, encoding='utf-8') as f:
        return yaml_safe_load(f)


def yaml_safe_load(f):
    try:
        import yaml
        return yaml.safe_load(f)
    except ImportError:                                     # pragma: no cover
        return json.load(f)


def _tool_call(name, args):
    if name == 'aegis_check':
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):              # 协议通道不受污染
            code, rep = ag.run_check(args['diff_path'], args.get('plan_path'),
                                     args.get('level'), None, False, False, True)
        text = json.dumps(rep, ensure_ascii=False)
        return {'content': [{'type': 'text', 'text': text}],
                'isError': code == ag.EXIT_BLOCK}

    if name == 'aegis_docs':
        parts, ok = [], True
        for kind, key in (('RC', 'rc'), ('FP', 'fp'), ('VR', 'vr')):
            path = args.get(key)
            if not path:
                continue
            doc = _load_yaml(path)
            probs = ag.validate_doc(kind, doc) if doc else ['%s 文件不存在或为空' % path]
            ok = ok and not probs
            parts.append('%s %s: %s' % (kind, os.path.basename(path),
                                        'OK' if not probs else '; '.join(probs)))
        return {'content': [{'type': 'text', 'text': '\n'.join(parts) or '未提供任何单据'}],
                'isError': not ok}

    raise ValueError('unknown tool: %s' % name)


def handle_request(req):
    """单条 JSON-RPC 请求 → 响应 dict（None 表示无需响应，如 notification）"""
    method = req.get('method')
    rid = req.get('id')
    if rid is None:                                         # notification
        return None
    base = {'jsonrpc': '2.0', 'id': rid}
    try:
        if method == 'initialize':
            return dict(base, result={
                'protocolVersion': PROTOCOL,
                'capabilities': {'tools': {}},
                'serverInfo': {'name': 'aegis-guard', 'version': '1.0'},
            })
        if method == 'tools/list':
            return dict(base, result={'tools': TOOLS})
        if method == 'tools/call':
            params = req.get('params') or {}
            return dict(base, result=_tool_call(params.get('name'),
                                                params.get('arguments') or {}))
        return dict(base, error={'code': -32601, 'message': 'method not found: %s' % method})
    except Exception as e:                                  # noqa: BLE001
        return dict(base, error={'code': -32000, 'message': str(e)})


def serve():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except ValueError:
            continue
        resp = handle_request(req)
        if resp is not None:
            sys.stdout.write(json.dumps(resp, ensure_ascii=False) + '\n')
            sys.stdout.flush()


if __name__ == '__main__':
    serve()
