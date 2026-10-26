# -*- coding: utf-8 -*-
"""GuardedWorkspace —— 模型写工具的护栏包装（规范 6.1 防线二的进程内实现）

机制（「写-检-回滚-回流」闭环）:
  1. 模型调用 write(path, content) —— 唯一的写入口；
  2. 对「自基线以来的全量 diff」跑 AEGIS 检查（预算按累计口径）；
  3. BLOCK 则本次写入不落盘（等效先回滚），违例报告以 ToolError
     形式抛回模型 —— 模型在循环内收到的是工具错误，不是劝告；
  4. PASS / WARN_ONLY 才真正写盘。

挂载方式（自研智能体）: 用本类替换/包装你现有的 write_file 工具实现，
模型侧接口不变（path + content），护栏在模型权限之外生效。
"""
import difflib
import os
import sys

_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)
import aegis_guard as ag                                   # noqa: E402


class ToolError(Exception):
    """以工具错误形态回流给模型的违规报告"""

    def __init__(self, report, reason):
        super(ToolError, self).__init__(reason)
        self.report = report

    def brief(self, limit=6):
        lines = []
        for v in (self.report.get('blocks') or [])[:limit]:
            where = (v.get('file') and ' @%s' % v['file']) or ''
            lines.append('[%s]%s %s' % (v['id'], where, v['msg']))
        for v in (self.report.get('warnings') or [])[:2]:
            lines.append('[W:%s] %s' % (v['id'], v['msg']))
        if not lines:
            lines.append(str(self))
        return '\n'.join(lines)


class ToolResult(object):
    def __init__(self, report):
        self.report = report
        self.ok = True


def make_file_diff(path, old_text, new_text):
    """生成单文件 unified diff；old_text 为 None 表示新文件"""
    if old_text is None:
        old_lines, fromfile = [], '/dev/null'
    else:
        old_lines, fromfile = old_text.splitlines(), 'a/' + path
    new_lines = new_text.splitlines()
    out = difflib.unified_diff(old_lines, new_lines,
                               fromfile=fromfile, tofile='b/' + path,
                               lineterm='')
    return '\n'.join(out)


class GuardedWorkspace(object):
    """受护栏保护的仓库工作区（P1 只读 / P3 沙箱写）"""

    def __init__(self, repo_dir, plan=None, level=None, config=None,
                 readonly=False, bridge=None, phase='P3'):
        self.repo = repo_dir
        self.plan = plan or {}
        self.level = level or (plan or {}).get('risk_level') or 'S2'
        self.cfg = ag.load_config(config)
        self.readonly = readonly
        self.bridge = bridge
        self.phase = phase
        self.baseline = {}          # path -> 触碰前内容（None=新文件）
        self.applied = {}           # path -> 已批准落盘内容
        self.intercepts = 0
        self.reports = []           # 每次检查的完整报告（留痕）

    # ---------- 基础 ----------
    def _abspath(self, path):
        return os.path.join(self.repo, path.lstrip('/'))

    def read(self, path):
        p = self._abspath(path)
        if not os.path.exists(p):
            return None
        with open(p, encoding='utf-8') as f:
            return f.read()

    # ---------- 写入口（核心） ----------
    def write(self, path, content):
        """模型唯一的写入口：先检查、后落盘；BLOCK 时写入不发生"""
        if self.readonly:
            rep = self._report([], {'files': 0, 'added': 0, 'removed': 0,
                                    'lines': 0, 'funcs': 0, 'deps': 0})
            rep['blocks'].append(ag.vio(
                'READONLY_LOCK', 'block',
                '只读锁生效（规范 5.2：P1 定位阶段禁止任何写操作）',
                '完成根因单并进入实施阶段后再写入'))
            rep['verdict'] = 'BLOCK'
            self._notify(rep)
            raise ToolError(rep, 'P1 只读锁：定位阶段禁止修改代码')

        if path not in self.baseline:
            self.baseline[path] = self.read(path)

        prev = self.applied.get(path)
        self.applied[path] = content                 # 先虚拟应用
        rep = self._check_full()

        if rep['verdict'] == 'BLOCK':
            # 回滚本次写入：虚拟区恢复，磁盘从未收到该内容
            if prev is None:
                self.applied.pop(path, None)
            else:
                self.applied[path] = prev
            self.intercepts += 1
            self._notify(rep, blocked=True)
            raise ToolError(rep, 'AEGIS Guard 拦截（铁律级违例，写入未落盘）')

        self._write_disk(path, content)
        self._notify(rep)
        return ToolResult(rep)

    # ---------- 检查与证据 ----------
    def full_diff(self):
        parts = [make_file_diff(p, self.baseline.get(p), self.applied[p])
                 for p in sorted(self.applied)]
        return '\n'.join(x for x in parts if x.strip())

    def last_report(self):
        return self.reports[-1] if self.reports else None

    def stats(self):
        rep = self.last_report()
        return rep.get('stats', {}) if rep else {}

    # ---------- 内部 ----------
    def _check_full(self):
        files = ag.parse_unified_diff(self.full_diff())
        out = []
        if self.plan:
            for p in ag.validate_doc('FP', self.plan):
                out.append(ag.vio('DOC_SCHEMA', 'block', p,
                                  '按附录 A 补齐三单（规范 6.4）'))
        ag.check_frozen(files, self.cfg, False, out)
        ag.check_tests(files, self.cfg, self.plan, out)
        ag.check_anti_patterns(files, self.cfg, out)
        st = ag.check_budget(files, self.cfg, self.level, self.plan, out)
        ag.check_plan(files, self.plan, out)
        return self._report(out, st)

    def _report(self, out, st):
        return ag.build_report([], out, st, self.level, self.plan)

    def _write_disk(self, path, content):
        p = self._abspath(path)
        d = os.path.dirname(p)
        if d:
            os.makedirs(d, exist_ok=True)
        with open(p, 'w', encoding='utf-8') as f:
            f.write(content)

    def _notify(self, rep, blocked=False):
        self.reports.append(rep)
        if self.bridge:
            self.bridge.emit('x-fix.guard', {
                'phase': self.phase, 'verdict': rep['verdict'],
                'violations': [v['id'] for v in (rep.get('blocks') or [])],
                'stats': rep.get('stats'),
            }, severity='warning' if blocked else 'info')
