#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AEGIS Guard —— 智能体代码修复护栏检查器原型
《智能体代码问题修复方法与标准规范（AEGIS V1.0）》第六章的实现载体。

检查维度（每项对应规范条款）:
  FROZEN_TOUCH   冻结清单守护      （6.3）
  ASSERT_REMOVED / TEST_SKIPPED / TEST_TOUCHED   测试篡改拦截（L3、9.1）
  AP001-AP007    禁改模式匹配      （L2、3.2 反模式库）
  BUDGET_*       diff 预算计数     （6.2）
  DOC_SCHEMA     三单契约校验      （6.4）
  L5_REDLINE     红绿基线强制      （L5、5.3）
  PLAN_DEVIATION 计划-实际比对     （L4/L8）

退出码: 0=PASS  1=BLOCK  2=仅警告  3=配置/用法错误
用法:
  python3 aegis_guard.py check --diff <diff文件|-> [--plan FP.yaml] [--level S2]
                               [--config config.default.yaml] [--json]
                               [--allow-frozen] [--strict]
  python3 aegis_guard.py docs  --fp docs/FP-003.yaml [--rc ...] [--vr ...]
  python3 aegis_guard.py demo
"""
import argparse
import fnmatch
import json
import os
import re
import sys

try:
    import yaml
except ImportError:                                    # pragma: no cover
    yaml = None

EXIT_PASS, EXIT_BLOCK, EXIT_WARN, EXIT_CONFIG = 0, 1, 2, 3
LEVELS = ('S0', 'S1', 'S2', 'S3', 'S4')
FUNC_DEF = re.compile(r'^\s*(async\s+def\s|def\s|class\s|func\s|function\s)')
LOG_HIGH = re.compile(r'\blog(ger)?\.(error|warning|critical)\b')
LOG_LOW = re.compile(r'\blog(ger)?\.(debug|info)\b')
ASSERT_RX = re.compile(r'\bassert\b')
SKIP_RX = re.compile(r'(mark\.(skip|xfail)|unittest\.skip|it\.skip\(|\.only\()')

# ---------------- 默认配置（config.default.yaml 的镜像，可被覆盖） ----------------
DEFAULT_CONFIG = {
    'budgets': {
        'S0': {'files': 8, 'lines': 300, 'funcs': 10, 'deps': 1},
        'S1': {'files': 8, 'lines': 300, 'funcs': 10, 'deps': 1},
        'S2': {'files': 5, 'lines': 150, 'funcs': 6, 'deps': 0},
        'S3': {'files': 3, 'lines': 60, 'funcs': 3, 'deps': 0},
        'S4': {'files': 3, 'lines': 60, 'funcs': 3, 'deps': 0},
    },
    'frozen_patterns': [
        '.github/workflows/*', '.gitlab-ci.yml', 'Jenkinsfile', '.circleci/*',
        '*.pem', '*.key', '*secret*', '.env*', 'infra/*', '*migrations*',
    ],
    'dep_manifests': [
        'package.json', 'package-lock.json', 'yarn.lock', 'requirements*.txt',
        'pyproject.toml', 'poetry.lock', 'pom.xml', 'go.mod', 'Cargo.toml',
    ],
    'test_path_patterns': [
        'tests/*', 'test/*', '**/test_*.py', '**/*_test.py', '**/conftest.py',
    ],
    'anti_patterns': [
        {'id': 'AP001', 'severity': 'block',
         'pattern': r'except[^:]*:\s*pass\b',
         'msg': '空捕获吞异常（铁律 L2：禁止压制表象）'},
        {'id': 'AP002', 'severity': 'block',
         'pattern': r'(?i)(password|passwd|secret|api[_-]?key|access[_-]?token|private[_-]?key)\s*[:=]\s*["\x27][^"\x27]{8,}["\x27]',
         'msg': '疑似硬编码凭证（禁止行为 11）'},
        {'id': 'AP003', 'severity': 'warn',
         'pattern': r'^\s*#\s*(def |class |return |import |if |for |while )',
         'msg': '疑似注释掉代码（禁止行为 6）'},
        {'id': 'AP004', 'severity': 'warn',
         'pattern': r'\b(TODO|FIXME|HACK)\b',
         'msg': '修复残留标记（铁律 L8：须披露）'},
        {'id': 'AP005', 'severity': 'warn',
         'pattern': r'^\s*(print\(|console\.log\(|System\.out\.print|fmt\.Print)',
         'msg': '调试输出残留（禁止行为 7 同类）'},
    ],
}

# ---------------- 三单 schema（规范 6.4 的机器可校验形态） ----------------
RC_SCHEMA = {
    'required': [('issue', str), ('symptom', str), ('chain', list),
                 ('root_cause', str), ('evidence', str), ('impact_scope', list),
                 ('confidence', str)],
    'enums': {'confidence': ['high', 'medium', 'low']},
}
FP_SCHEMA = {
    'required': [('issue', str), ('risk_level', str), ('root_cause_ref', str),
                 ('changes', list), ('budget', dict), ('verification', dict),
                 ('rollback', str)],
    'enums': {'risk_level': list(LEVELS)},
    'changes_item': [('file', str), ('symbol', str), ('intent', str)],
    'verification_required': [('red_baseline', str), ('regression_set', str)],
}
VR_SCHEMA = {
    'required': [('plan_ref', str), ('gates', dict), ('diff_vs_plan', dict)],
}


def deep_merge(base, over):
    out = dict(base)
    for k, v in (over or {}).items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = deep_merge(out[k], v)
        else:
            out[k] = v
    return out


def load_yaml(path):
    if yaml is None:
        raise RuntimeError('PyYAML 不可用，无法读取 ' + path)
    with open(path, encoding='utf-8') as f:
        return yaml.safe_load(f)


def load_config(path):
    cfg = json.loads(json.dumps(DEFAULT_CONFIG))
    if path:
        cfg = deep_merge(cfg, load_yaml(path))
    return cfg


# ---------------- unified diff 解析 ----------------
class FileDiff(object):
    def __init__(self, path):
        self.path = path
        self.added = []
        self.removed = []
        self.is_new = False
        self.is_deleted = False


def _strip_prefix(p):
    return p[2:] if p.startswith(('a/', 'b/')) else p


def parse_unified_diff(text):
    files, cur, in_hunk, old_path = [], None, False, None
    for line in text.splitlines():
        if line.startswith('diff --git'):
            cur, in_hunk, old_path = None, False, None
            continue
        if line.startswith('--- '):
            old_path = line[4:].strip()
            if old_path == '/dev/null':
                old_path = None
            continue
        if line.startswith('+++ '):
            p = line[4:].strip()
            if p == '/dev/null':
                cur = FileDiff(_strip_prefix(old_path or 'unknown'))
                cur.is_deleted = True
            else:
                cur = FileDiff(_strip_prefix(p))
                cur.is_new = (old_path is None)
            files.append(cur)
            in_hunk = False
            continue
        if line.startswith('@@'):
            in_hunk = True
            continue
        if not in_hunk or cur is None:
            continue
        if line.startswith('+'):
            cur.added.append(line[1:])
        elif line.startswith('-'):
            cur.removed.append(line[1:])
    return files


def match_any(path, patterns):
    return any(fnmatch.fnmatch(path, p) for p in patterns)


def is_test_path(path, cfg):
    if os.path.basename(path) == 'conftest.py':
        return True
    return match_any(path, cfg['test_path_patterns'])


# ---------------- 检查器（每个对应规范条款） ----------------
def vio(id_, severity, msg, advice, file=None, line=None):
    return {'id': id_, 'severity': severity, 'msg': msg, 'advice': advice,
            'file': file, 'line': line}


def check_frozen(files, cfg, allow_frozen, out):
    for f in files:
        hits = [p for p in cfg['frozen_patterns'] if fnmatch.fnmatch(f.path, p)]
        if hits:
            out.append(vio(
                'FROZEN_TOUCH', 'warn' if allow_frozen else 'block',
                '触碰冻结清单资产: %s' % ', '.join(hits),
                '停止实施并提交 GATE 审批例外（规范 6.3）；人工批准后以 --allow-frozen 复核留痕',
                file=f.path))


def check_tests(files, cfg, plan, out):
    approved = bool(plan and plan.get('test_defect_approval'))
    for f in files:
        if not is_test_path(f.path, cfg) or f.is_new:
            continue
        for l in f.removed:
            if ASSERT_RX.search(l):
                out.append(vio(
                    'ASSERT_REMOVED', 'block',
                    '测试断言被删除或改写（铁律 L3：测试神圣律）',
                    '回退该改动；若测试本身有误，走测试缺陷认定流程（双人审批，规范 9.1）',
                    file=f.path, line=l.strip()[:64]))
        for l in f.added:
            if SKIP_RX.search(l):
                out.append(vio(
                    'TEST_SKIPPED', 'block',
                    '新增测试跳过/排除（铁律 L3）',
                    '禁止以 skip 换绿；确需排除请走测试缺陷认定流程',
                    file=f.path, line=l.strip()[:64]))
        if not approved:
            out.append(vio(
                'TEST_TOUCHED', 'warn',
                '修改既有测试文件且无测试缺陷认定记录',
                '补《测试缺陷认定单》或回退测试改动',
                file=f.path))


def check_anti_patterns(files, cfg, out):
    pats = [(p['id'], p['severity'], re.compile(p['pattern']), p['msg'])
            for p in cfg['anti_patterns']]
    for f in files:
        for l in f.added:
            for pid, sev, rx, msg in pats:
                if rx.search(l):
                    out.append(vio(pid, sev, msg,
                                   '按铁律 L2 修正根因，或按 L8 即时披露',
                                   file=f.path, line=l.strip()[:64]))
        if any(LOG_HIGH.search(l) for l in f.removed) and \
           any(LOG_LOW.search(l) for l in f.added):
            out.append(vio('AP007', 'warn',
                           '疑似日志级别降级（铁律 L2）',
                           '确认未以调低日志掩盖故障信号', file=f.path))


def diff_stats(files):
    added = sum(len(f.added) for f in files)
    removed = sum(len(f.removed) for f in files)
    funcs = sum(1 for f in files for l in (f.added + f.removed) if FUNC_DEF.match(l))
    return {'files': len(files), 'added': added, 'removed': removed,
            'lines': added + removed, 'funcs': funcs}


def check_budget(files, cfg, level, plan, out):
    b = dict(cfg['budgets'].get(level, cfg['budgets']['S3']))
    if plan and isinstance(plan.get('budget'), dict):
        for k in b:
            v = plan['budget'].get(k)
            if isinstance(v, int) and v >= 0:
                b[k] = min(b[k], v)          # 方案单只能收紧预算，不能放大
    st = diff_stats(files)
    st['deps'] = sum(1 for f in files if match_any(f.path, cfg['dep_manifests']))
    for key, cap in b.items():
        used = st[key]
        if cap == 0:
            if used > 0:
                out.append(vio('BUDGET_DEPS', 'block',
                               '%s 变更不在预算内（%d 项，规范 6.2）' % ('依赖', used),
                               '依赖清单默认冻结；升级修复按 4.3 决策序并走 GATE 审批'))
            continue
        if used > cap:
            out.append(vio('BUDGET_EXCEED', 'block',
                           '超出 %s 预算: %d > %d（铁律 L1 / 规范 6.2）' % (key, used, cap),
                           '强制拆单或升级审批；大 diff 通常在修症状或夹带私货'))
        elif cap and used >= 0.9 * cap:
            out.append(vio('BUDGET_NEAR', 'warn',
                           '已达 %s 预算 %d%%（%d/%d），立即收尾' % (key, used * 100 // cap, used, cap),
                           '停止新增变更，转入验证阶段'))
    return st


def check_plan(files, plan, out):
    if not plan:
        return
    declared = [c.get('file') for c in plan.get('changes', []) if c.get('file')]
    actual = {f.path for f in files}
    for f in files:
        if f.path not in declared:
            out.append(vio('PLAN_DEVIATION', 'block',
                           '计划外文件变更（铁律 L4/L8）',
                           '更新方案单变更清单并说明，或回退该文件变更', file=f.path))
    untouched = [d for d in declared if d not in actual]
    for d in untouched:
        out.append(vio('PLAN_GAP', 'warn',
                       '方案单声明了变更但 diff 未体现',
                       '核对是否漏改或方案单过期', file=d))


def _type_name(t):
    return {str: '字符串', list: '数组', dict: '对象'}.get(t, str(t))


def validate_doc(kind, doc):
    """三单 schema 校验，返回违例列表"""
    problems = []
    schema = {'RC': RC_SCHEMA, 'FP': FP_SCHEMA, 'VR': VR_SCHEMA}[kind]
    if not isinstance(doc, dict):
        return ['%s 单不是映射结构' % kind]
    for field, typ in schema['required']:
        if field not in doc or doc[field] in (None, ''):
            problems.append('%s 单缺少必填字段 %s（%s 单规范 6.4）' % (kind, field, kind))
        elif not isinstance(doc[field], typ):
            problems.append('%s 单字段 %s 应为%s' % (kind, field, _type_name(typ)))
    for field, allowed in schema.get('enums', {}).items():
        if doc.get(field) and doc[field] not in allowed:
            problems.append('%s 单字段 %s 取值非法: %s' % (kind, field, doc[field]))
    if kind == 'FP':
        for i, c in enumerate(doc.get('changes') or []):
            for field, typ in schema['changes_item']:
                if not isinstance(c, dict) or field not in c:
                    problems.append('FP 单 changes[%d] 缺少 %s' % (i, field))
        ver = doc.get('verification') or {}
        for field, typ in schema['verification_required']:
            if field not in ver or not str(ver.get(field) or '').strip():
                problems.append('FP 单 verification.%s 缺失' % field)
        if not str(ver.get('red_baseline') or '').strip() and \
           not str(ver.get('rescan') or '').strip():
            problems.append('FP 单既无红基线亦无复扫替代证据（铁律 L5 / 规范 5.3）')
    return problems


# ---------------- 汇总与输出 ----------------
def build_report(files, vios, st, level, plan):
    blocks = [v for v in vios if v['severity'] == 'block']
    warns = [v for v in vios if v['severity'] == 'warn']
    if blocks:
        verdict = 'BLOCK'
    elif warns:
        verdict = 'WARN_ONLY'
    else:
        verdict = 'PASS'
    return {'tool': 'aegis-guard', 'spec': 'AEGIS V1.0', 'verdict': verdict,
            'level': level, 'issue': (plan or {}).get('issue'),
            'stats': st,
            'blocks': blocks, 'warnings': warns,
            'n_violations': len(vios)}


def print_report(rep, allow_frozen):
    st = rep['stats']
    print('=' * 62)
    print('AEGIS Guard 检查报告   规范: AEGIS V1.0   问题单: %s   级别: %s'
          % (rep.get('issue') or '-', rep['level']))
    print('变更规模: 文件 %d · +%d/-%d 行 · 函数 %d · 依赖 %d'
          % (st['files'], st['added'], st['removed'], st['funcs'], st['deps']))
    mark = {'BLOCK': '[BLOCK] 存在铁律级违例，变更被拦截',
            'WARN_ONLY': '[WARN] 仅警告级事项，放行但需披露',
            'PASS': '[PASS] 全部检查通过'}
    print('判定: %s' % mark[rep['verdict']])
    for tag, items in (('违例', rep['blocks']), ('警告', rep['warnings'])):
        for v in items:
            where = v['file'] or '-'
            print('  %-6s %-16s %s' % ('[' + tag + ']', v['id'], v['msg']))
            print('         位置: %s%s' % (where,
                  ('  |  ' + v['line']) if v.get('line') else ''))
            print('         处置: %s' % v['advice'])
    if rep['verdict'] == 'BLOCK':
        print('-' * 62)
        print('BLOCK 由铁律触发，Agent 不可自行关闭（规范 3.3）；'
              '唯一出路: 回退、拆单或走 GATE 例外通道。')
    print('=' * 62)


def read_diff(path):
    if path == '-':
        return sys.stdin.read()
    with open(path, encoding='utf-8') as f:
        return f.read()


def run_check(diff_path, plan_path, level, config_path, allow_frozen,
              strict, as_json):
    cfg = load_config(config_path)
    files = parse_unified_diff(read_diff(diff_path))
    plan = load_yaml(plan_path) if plan_path else None
    lv = level or (plan or {}).get('risk_level') or 'S2'
    if lv not in LEVELS:
        print('错误: 未知问题级别 %s' % lv, file=sys.stderr)
        return EXIT_CONFIG, None
    out = []
    if plan:
        for p in validate_doc('FP', plan):
            out.append(vio('DOC_SCHEMA', 'block', p,
                           '按附录 A 模板补齐三单；schema 不过视为阶段未完成（规范 6.4）'))
    check_frozen(files, cfg, allow_frozen, out)
    check_tests(files, cfg, plan, out)
    check_anti_patterns(files, cfg, out)
    st = check_budget(files, cfg, lv, plan, out)
    check_plan(files, plan, out)
    rep = build_report(files, out, st, lv, plan)
    if as_json:
        print(json.dumps(rep, ensure_ascii=False, indent=2))
    else:
        print_report(rep, allow_frozen)
    if rep['verdict'] == 'BLOCK':
        code = EXIT_BLOCK
    elif rep['verdict'] == 'WARN_ONLY':
        code = EXIT_BLOCK if strict else EXIT_WARN
    else:
        code = EXIT_PASS
    return code, rep


def cmd_docs(args):
    bad = 0
    for kind, path in (('RC', args.rc), ('FP', args.fp), ('VR', args.vr)):
        if not path:
            continue
        doc = load_yaml(path)
        probs = validate_doc(kind, doc)
        if probs:
            bad += len(probs)
            print('[FAIL] %s (%s)' % (kind, os.path.basename(path)))
            for p in probs:
                print('       - %s' % p)
        else:
            print('[OK]   %s (%s) 契约校验通过' % (kind, os.path.basename(path)))
    return EXIT_BLOCK if bad else EXIT_PASS


def cmd_demo(args):
    base = os.path.dirname(os.path.abspath(__file__))
    cfg = os.path.join(base, 'config.default.yaml')
    print('场景一 违规修复（吞异常+硬编码凭证+改测试+触冻结+超预算+计划外文件）')
    print('预期: BLOCK，各检查器全部命中\n')
    c1, _ = run_check(os.path.join(base, 'fixtures/bad.diff'),
                      os.path.join(base, 'docs/FP-bad.yaml'),
                      None, cfg, False, False, False)
    print('\n场景二 合规修复（预算内、三单齐备、红基线在场）')
    print('预期: PASS\n')
    c2, _ = run_check(os.path.join(base, 'fixtures/good.diff'),
                      os.path.join(base, 'docs/FP-003.yaml'),
                      None, cfg, False, False, False)
    print('\n演示结论: 场景一退出码 %d（BLOCK）、场景二退出码 %d（%s）'
          % (c1, c2, 'PASS' if c2 == EXIT_PASS else '非 PASS'))
    print('退出码约定: 0=PASS 1=BLOCK 2=仅警告 3=配置错误 —— 即 CI 门禁的接线方式')
    return EXIT_PASS


def main(argv=None):
    ap = argparse.ArgumentParser(prog='aegis-guard',
                                 description='AEGIS V1.0 修复护栏检查器原型')
    sub = ap.add_subparsers(dest='cmd', required=True)

    p = sub.add_parser('check', help='检查一个修复 diff')
    p.add_argument('--diff', required=True, help="unified diff 文件路径，或 '-' 读 stdin")
    p.add_argument('--plan', help='修复方案单 FP.yaml（缺失则跳过契约与比对检查）')
    p.add_argument('--level', choices=LEVELS, help='问题级别（默认取 FP.risk_level，再默认 S2）')
    p.add_argument('--config', help='配置文件（默认内置）')
    p.add_argument('--json', action='store_true', help='输出机器可读 JSON')
    p.add_argument('--allow-frozen', action='store_true',
                   help='人工批准的冻结例外（Agent 不得使用；降级为警告并留痕）')
    p.add_argument('--strict', action='store_true', help='警告也判失败')

    d = sub.add_parser('docs', help='三单契约校验')
    d.add_argument('--rc'); d.add_argument('--fp'); d.add_argument('--vr')

    m = sub.add_parser('demo', help='运行内置违规/合规双场景演示')
    args = ap.parse_args(argv)
    if args.cmd == 'check':
        code, _ = run_check(args.diff, args.plan, args.level, args.config,
                            args.allow_frozen, args.strict, args.json)
    elif args.cmd == 'docs':
        code = cmd_docs(args)
    else:
        code = cmd_demo(args)
    sys.exit(code)


if __name__ == '__main__':
    main()
