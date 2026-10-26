# -*- coding: utf-8 -*-
"""StageController —— P0-P6 作业阶段门（规范 5.1 / 5.2 / 5.4 的进程内实现）

职责:
  - 阶段只能按序推进（P0→P1→P2→GATE→P3→P4→P5→P6）；
  - 三单是阶段门票: accept_doc() 调 AEGIS schema 校验，不过则该阶段
    产出无效，模型必须重做；
  - 安灯升级: 同一阶段连续 2 次产出不合格 → 强制停止并转人工（规范 5.4）；
  - GATE 审批矩阵（规范 9.1 简化版）: S3/S4 且无冻结/依赖/测试修改
    走 D0 免审自动放行；其余升 D2 人工确认。
"""
import os
import sys

_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)
import aegis_guard as ag                                   # noqa: E402

PHASES = ['P0', 'P1', 'P2', 'GATE', 'P3', 'P4', 'P5', 'P6']

PHASE_BRIEF = {
    'P0': '准入: 五查（可复现/未重复/范围明确/判据明确/来源可信）',
    'P1': '只读定位: 产出根因单 RC，禁止任何写操作',
    'P2': '方案设计: 产出方案单 FP（变更清单/预算/验证计划/回滚）',
    'GATE': '审批: 按矩阵 D0-D3 放行、修改或驳回',
    'P3': '受控实施: 沙箱写 + 预算内 + 冻结外 + 白名单工具',
    'P4': '三层验证: 静态一致性 / 定向验证（红转绿） / 回归选择集',
    'P5': '交付合入: PR 强制字段齐备，评审通过',
    'P6': '复盘反哺: 教训条目入库，防错规则建议',
}


class StageController(object):

    def __init__(self, issue, level, bridge=None):
        self.issue = issue
        self.level = level
        self.bridge = bridge
        self.phase = 'P0'
        self.fail = {p: 0 for p in PHASES}
        self.escalated = False
        self.docs = {}
        self.transitions = [('P0', 'init')]

    # ---------- 阶段推进 ----------
    def go(self, next_phase):
        if next_phase not in PHASES:
            raise ValueError('未知阶段 %s' % next_phase)
        cur = PHASES.index(self.phase)
        if PHASES.index(next_phase) != cur + 1:
            raise ValueError('阶段越序: %s -> %s（规范 5.2 只能按序推进）'
                             % (self.phase, next_phase))
        if self.escalated:
            raise RuntimeError('安灯已升级，作业冻结，等待人工接管（规范 5.4）')
        self.phase = next_phase
        self.transitions.append((next_phase, 'ok'))
        if self.bridge:
            self.bridge.emit('x-fix.stage', {'phase': next_phase,
                                             'brief': PHASE_BRIEF[next_phase]})

    # ---------- 三单门票 ----------
    def accept_doc(self, kind, doc):
        """schema 不过 = 阶段产出无效；连续 2 次不合格触发安灯"""
        probs = ag.validate_doc(kind, doc)
        if probs:
            self.fail[self.phase] += 1
            if self.fail[self.phase] >= 2:
                self.escalated = True
                if self.bridge:
                    self.bridge.emit('x-fix.escalate', {
                        'phase': self.phase, 'kind': kind,
                        'problems': probs}, severity='critical')
            return probs
        self.docs[kind] = doc
        return []

    # ---------- GATE 审批矩阵 ----------
    def gate(self, fp, human_approve=None):
        lvl = fp.get('risk_level') or self.level
        touches_frozen = bool(fp.get('frozen_exception'))
        dep_changes = int((fp.get('budget') or {}).get('deps') or 0)
        test_mod = bool(fp.get('test_defect_approval'))
        if lvl in ('S3', 'S4') and not touches_frozen \
                and dep_changes == 0 and not test_mod:
            if self.bridge:
                self.bridge.emit('decision.required', {
                    'level': 'D0', 'auto': True,
                    'reason': 'S%s 预算内无冻结/依赖/测试修改' % lvl[-1]})
            return 'D0 免审（矩阵自动放行）'
        if human_approve is None:
            if self.bridge:
                self.bridge.emit('decision.required', {
                    'level': 'D2', 'auto': False,
                    'reason': '命中审批矩阵: %s' % ', '.join(
                        [x for x, hit in (('S0-S2', lvl in ('S0', 'S1', 'S2')),
                                          ('冻结例外', touches_frozen),
                                          ('依赖变更', dep_changes > 0),
                                          ('测试修改', test_mod)) if hit])})
            return None                              # 等待人工
        return human_approve(fp)                     # 人工回调裁决
