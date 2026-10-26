#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
demo_agent —— 自研智能体集成全流程演示（AEGIS 集成层的机制自证）

演示路径: P0 准入 → P1 只读定位（含一次 schema 被拒）→ P2 方案单 →
GATE D0 放行 → P3a 红基线（先写复现用例，跑出红）→ P3b 受控实施
（坏修复被护栏拦截两次 → 工具错误回流 → 模型收敛为好修复）→
P4 三层验证（红转绿 + 回归选择集）→ P5 交付 → P6 复盘 + ATLAS 留痕。

用法: python3 demo_agent.py [--stubborn]
      --stubborn 演示模型拒不收敛 → 连续失败触发安灯升级转人工。
"""
import argparse
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

from aegis_agent import (GuardedWorkspace, ToolError,           # noqa: E402
                         StageController, AtlasBridge,
                         build_system_prompt, build_stage_prompt)

# ---------------- 演示问题单与三单 ----------------
ISSUE = {'id': 'VUL-2026-0928-001', 'level': 'S3',
         'summary': 'SAST 报告 token 验签接受 alg=none（CWE-347）',
         'source': 'SAST 扫描报告 #9007'}

RC_PARTIAL = {                       # 模型第一次提交: 缺 chain/evidence
    'issue': ISSUE['id'],
    'symptom': 'verify() 未校验 alg 即验签',
    'root_cause': '验签入口缺少算法白名单',
    'impact_scope': ['token.py'],
    'confidence': 'high',
}
RC = dict(RC_PARTIAL, chain=[
    '为何告警: verify 未校验 alg 头即验签',
    '为何未校验: 修复人不知道 alg 可控',
    '为何未拦截: 无针对 JWT 算法的 lint 规则',
], evidence='复现用例 tests/test_alg_none.py（修复前红）')

FP = {
    'issue': ISSUE['id'], 'risk_level': 'S3', 'root_cause_ref': 'RC-001',
    'test_defect_approval': False,
    'changes': [
        {'file': 'token.py', 'symbol': 'verify',
         'intent': '增加算法白名单，拒绝 alg=none'},
        {'file': 'tests/test_alg_none.py', 'symbol': 'test_rejects_none_alg',
         'intent': '红基线回归用例'},
    ],
    'budget': {'files': 3, 'lines': 40, 'funcs': 2, 'deps': 0},
    'verification': {
        'red_baseline': 'tests/test_alg_none.py 修复前红、修复后绿',
        'regression_set': '影响面选择集 2 例',
    },
    'rollback': 'git revert；影响面仅 token.py',
    'approvals': [],
}

# ---------------- 模型产出的代码版本 ----------------
RED_TEST = '''import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import token


def test_rejects_none_alg():
    try:
        token.verify("h.p.u1", "k", alg="none")
    except token.InvalidAlgorithm:
        return True
    raise AssertionError("alg=none 未被拒绝（红基线未转绿）")


if __name__ == "__main__":
    test_rejects_none_alg()
    sys.stdout.write("GREEN\\n")
'''

BAD_TOKEN = '''"""JWT 验签入口（演示仓库）"""
API_KEY = "sk-live-9f8a7b6c1234abcd"


class InvalidAlgorithm(Exception):
    pass


def verify(token, key, alg):
    try:
        payload = _verify_sig(token, key, alg)
    except Exception: pass  # 保证主流程不中断
    print("verify ok")
    return payload


def _verify_sig(token, key, alg):
    if token.count(".") != 2:
        raise ValueError("malformed token")
    return {"sub": token.split(".")[2]}
'''

GOOD_TOKEN = '''"""JWT 验签入口（演示仓库）"""
ALLOWED_ALGS = ("HS256",)


class InvalidAlgorithm(Exception):
    pass


def verify(token, key, alg):
    if alg not in ALLOWED_ALGS:
        raise InvalidAlgorithm(alg)
    payload = _verify_sig(token, key, alg)
    return payload


def _verify_sig(token, key, alg):
    if token.count(".") != 2:
        raise ValueError("malformed token")
    return {"sub": token.split(".")[2]}
'''

BAD_CI = ('name: CI\non: [push]\njobs:\n  test:\n    runs-on: ubuntu-latest\n'
          '    steps:\n      - run: python3 -m pytest tests/ -q || true\n')


# ---------------- 脚本化模型（模拟 LLM 行为） ----------------
class FakeModel(object):
    """第一次尝试典型坏修复（吞异常+硬编码凭证+调门禁），收到工具错误后收敛"""

    def __init__(self):
        self.feedback_seen = []            # 收到的工具错误（回流证据）

    def apply(self, ws, round_no, stubborn=False):
        errs = []
        if round_no == 1 or stubborn:
            plan = [('token.py', BAD_TOKEN), ('.github/workflows/ci.yml', BAD_CI)]
        else:
            plan = [('token.py', GOOD_TOKEN)]
        for path, content in plan:
            try:
                ws.write(path, content)
            except ToolError as e:
                errs.append((path, e))
        if errs:
            for path, e in errs:
                self.feedback_seen.append({'path': path,
                                           'brief': e.brief()})
            raise _AttemptFailed(errs)
        return plan


class _AttemptFailed(Exception):
    def __init__(self, errs):
        super(_AttemptFailed, self).__init__('attempt failed')
        self.errs = errs


# ---------------- 工具: 执行测试（红绿证据） ----------------
def run_test(repo, name):
    p = subprocess.run([sys.executable, os.path.join('tests', name)],
                       cwd=repo, capture_output=True, text=True)
    return p.returncode, (p.stdout or '').strip()


# ---------------- 主流程 ----------------
def run_demo(repo=None, stubborn=False, verbose=True):
    def say(*a):
        if verbose:
            print(*a)

    if repo is None:
        repo = tempfile.mkdtemp(prefix='aegis-demo-')
        src = os.path.join(HERE, 'demo_repo')
        for item in ('token.py', 'tests', '.github'):
            s = os.path.join(src, item)
            d = os.path.join(repo, item)
            (shutil.copytree if os.path.isdir(s) else shutil.copy)(s, d)

    bridge = AtlasBridge(os.path.join(repo, 'atlas-events.jsonl'))
    ctrl = StageController(ISSUE['id'], ISSUE['level'], bridge)
    model = FakeModel()
    summary = {'repo': repo, 'red': None, 'green': None, 'converged': None,
               'rounds': 0, 'escalated': False, 'intercepts': 0,
               'pr': None, 'events': 0}

    say('=' * 68)
    say('AEGIS 集成演示: 自研智能体修复 %s（%s）' % (ISSUE['id'], ISSUE['summary']))
    say('=' * 68)

    # ---- P0 准入 ----
    ctrl.go('P1')
    say('\n[P0] 准入五查通过（来源可信/可复现/未重复/范围明确/判据明确）')

    # ---- P1 只读定位 ----
    ws_ro = GuardedWorkspace(repo, level=ISSUE['level'],
                             readonly=True, bridge=bridge, phase='P1')
    say('\n[P1] 注入提示词（节选）:')
    for line in build_system_prompt(ISSUE['id'], ISSUE['level'],
                                    FP['budget']).splitlines()[:6]:
        say('      ' + line)
    say('      ...')
    say(build_stage_prompt('P1', ISSUE['id']))
    try:
        ws_ro.write('token.py', 'x = 1')
    except ToolError:
        say('      [拦截] 模型试图在 P1 写文件 → 只读锁生效（规范 5.2）')
    probs = ctrl.accept_doc('RC', RC_PARTIAL)
    if probs:
        say('      [阶段门] RC schema 不合格: %s → 退回重做' % probs[0])
    ctrl.accept_doc('RC', RC)
    say('      [OK] 根因单 RC 接受: %s' % RC['root_cause'])
    ctrl.go('P2')

    # ---- P2 方案设计 ----
    ctrl.accept_doc('FP', FP)
    say('\n[P2] 方案单 FP 接受: 变更 %d 文件, 预算 %s'
        % (len(FP['changes']), FP['budget']))
    ctrl.go('GATE')

    # ---- GATE ----
    decision = ctrl.gate(FP)
    say('\n[GATE] %s' % decision)
    ctrl.go('P3')

    # ---- P3a 红基线 ----
    ws = GuardedWorkspace(repo, plan=FP, level=ISSUE['level'],
                          bridge=bridge, phase='P3')
    say('\n[P3a] 先写复现用例取得红基线（铁律 L5）')
    ws.write('tests/test_alg_none.py', RED_TEST)
    rc_red, _ = run_test(repo, 'test_alg_none.py')
    summary['red'] = (rc_red != 0)
    say('      红基线: exit=%d → %s（证据已存档，事后改测试将与存证冲突）'
        % (rc_red, '红' if rc_red else '意外绿'))

    # ---- P3b 受控实施: 坏修复被拦 → 反馈回流 → 收敛 ----
    say('\n[P3b] 受控实施（写-检-回滚-回流闭环）')
    converged = False
    for round_no in (1, 2, 3):
        summary['rounds'] = round_no
        try:
            model.apply(ws, round_no, stubborn=stubborn)
            converged = True
            break
        except _AttemptFailed as af:
            say('      第 %d 轮: %d 次写入被护栏拒绝，工具错误回流给模型:'
                % (round_no, len(af.errs)))
            for path, e in af.errs:
                for line in e.brief().splitlines()[:3]:
                    say('        %s' % line)
            ws_intercepts = ws.intercepts
    summary['converged'] = converged
    summary['intercepts'] = ws.intercepts

    if converged:
        st = ws.stats()
        say('      第 %d 轮收敛: 实际 diff %s（与方案单声明一致）'
            % (round_no, {k: st[k] for k in ('files', 'added', 'removed')}))
        ctrl.go('P4')

        # ---- P4 三层验证 ----
        say('\n[P4] 三层验证门')
        rep = ws.last_report()
        say('      门一 静态一致性: %s（护栏终检 %s，零违例）'
            % ('PASS', rep['verdict']))
        rc_green, out = run_test(repo, 'test_alg_none.py')
        summary['green'] = (rc_green == 0)
        say('      门二 定向验证: 红转绿 → %s'
            % ('绿（PASS）' if rc_green == 0 else '仍红（FAIL）'))
        rc2, _ = run_test(repo, 'test_smoke.py')
        say('      门三 回归选择集: smoke=%s, redline=%s（2/2）'
            % ('绿' if rc2 == 0 else '红', '绿' if rc_green == 0 else '红'))
        vr = {
            'plan_ref': 'FP-001',
            'gates': {'gate1': 'pass', 'gate2': '红转绿', 'gate3': '2/2'},
            'diff_vs_plan': {'planned_files': 2, 'actual_files': 2,
                             'deviation': 'none'},
            'residual_risks': [],
        }
        ctrl.accept_doc('VR', vr)
        ctrl.go('P5')

        # ---- P5 交付 ----
        pr = {'issue': ISSUE['id'], 'root_cause': RC['root_cause'],
              'impact': FP['changes'], 'evidence': '红基线 exit=1 → 绿 exit=0',
              'rollback': FP['rollback'], 'diff': ws.stats(),
              'verification': vr}
        summary['pr'] = pr
        say('\n[P5] 交付 PR（强制字段齐备）: %s' % pr['evidence'])
        ctrl.go('P6')

        # ---- P6 复盘 ----
        lesson = 'JWT alg 校验应集中白名单管理；建议新增 lint 规则拦截 alg=none'
        bridge.emit('x-fix.retrospective', {'lessons': [lesson]})
        say('\n[P6] 复盘反哺: %s' % lesson)
    else:
        summary['escalated'] = True
        say('\n[安灯] 模型 3 轮未收敛 → 升级转人工（规范 5.4）')
        bridge.emit('x-fix.escalate', {'reason': '连续多轮未收敛'},
                    severity='critical')

    summary['events'] = len(bridge.events)
    say('\n' + '=' * 68)
    say('结论: 收敛=%s 轮次=%d 拦截=%d 红基线=%s 红转绿=%s ATLAS事件=%d'
        % (converged, summary['rounds'], summary['intercepts'],
           summary['red'], summary['green'], summary['events']))
    say('留痕: %s' % bridge.out_path)
    say('=' * 68)
    return summary


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--stubborn', action='store_true',
                    help='演示模型拒不收敛 → 安灯升级')
    args = ap.parse_args()
    run_demo(stubborn=args.stubborn)
    return 0


if __name__ == '__main__':
    sys.exit(main())
