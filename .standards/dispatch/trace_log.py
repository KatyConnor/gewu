#!/usr/bin/env python3
# trace_log.py — 规范体系链路监控（V1.9.34 · AETC 域机制扩展）
# 用法:
#   python3 .standards/dispatch/trace_log.py --report          # 时间线聚合+漏斗分析
#   python3 .standards/dispatch/trace_log.py --tail 20         # 最近 N 条事件
#   from trigger_check import ...; import trace_log; trace_log.append({...})
# 事件模型（JSONL 单行一事件, 每行必含 ts+event）:
#   route           路由判定（trigger_check 三入口自动写·机器强制）
#   entry_read      规范入口被读（Agent 自报·AETC 协议约束）
#   role_card_read  角色卡被读（Agent 自报·三十秒版直连的读卡证据）
#   gate            里程碑门禁过点（CR P0/UTSS 准出等）
#   install_selftest 安装后自测（install.sh 写·装痕显式分家）
# 可靠性分层：route/install_selftest/gate=机器强制; entry_read/role_card_read=审计级
# （缺报本身是断裂信号——与 route 流量对照即得断层位置）
# 〔V1.9.36 口径修正（判型：10-24 黄金测试+trace dump 三证）〕
#   1) trace-log 定位=**机器轨诊断**，不作为会话真实性证据——会话真实性以对话证据
#      （黄金测试质询「引用原文」）为准；
#   2) source 三口径：agent（Agent 会话流·显式标记）/ manual-test（人工终端测试·
#      **不带显式 source 的保守默认**）/ install_selftest+reltest（装痕/发版测试）——
#      宁可漏计不可虚计（V1.9.35 前的 source=agent 默认值曾把人工测试混计入真实流量，
#      10-23 00:38 的 11 条 route 实为装后批量验证，已公开勘误）；
#   3) 判定-读取绑定：trigger_check --read-entry 一条命令落 route+entry_read 同链。
import json
import os
import sys
import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
try:
    from trigger_check import _proj_root  # 单一正本：三态锚定复用（P1 安装/开发仓/P2 全局）
except ImportError:
    def _proj_root():
        return os.path.dirname(os.path.dirname(HERE))

TRACE_FILE = "trace-log.jsonl"
EVENTS = ("route", "entry_read", "role_card_read", "gate", "install_selftest")


def trace_path():
    return os.path.join(_proj_root(), ".standards", "audit", TRACE_FILE)


def append(entry):
    """追加一条链路事件。必填 ts（自动）/event；其余字段按事件类型自便。"""
    try:
        entry = dict(entry)
        entry.setdefault("ts", datetime.datetime.now().isoformat(timespec="seconds"))
        ev = entry.get("event", "")
        if ev not in EVENTS:
            print("[TRACE] 拒写未知事件类型: %s（合法: %s）" % (ev, "/".join(EVENTS)))
            return None
        if ev == "route" and "source" not in entry:
            entry["source"] = "manual-test"   # V1.9.36 保守默认：人工终端测试；agent 须显式传入（宁可漏计不可虚计）
        d = os.path.dirname(trace_path())
        os.makedirs(d, exist_ok=True)
        with open(trace_path(), "a", encoding="utf-8") as f:
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")
        return trace_path()
    except OSError:
        return None


def _read():
    if not os.path.isfile(trace_path()):
        return []
    out = []
    for line in open(trace_path(), encoding="utf-8"):
        line = line.strip()
        if not line:
            continue
        try:
            out.append(json.loads(line))
        except ValueError:
            out.append({"ts": "?", "event": "(解析失败行)", "raw": line[:60]})
    return out


def report():
    rows = _read()
    if not rows:
        print("链路事件流为空：%s" % trace_path())
        print("（无事件=路由层零流量或未接线——先跑 trigger_check --text 验证 route 事件写入）")
        return 0
    import collections
    by_ev = collections.Counter(r.get("event", "?") for r in rows)
    by_spec = collections.Counter(r.get("spec", r.get("role", "?")) for r in rows if r.get("event") == "route")
    days = collections.Counter(r.get("ts", "?")[:10] for r in rows)
    print("═ 规范体系链路监控报告（%s）" % trace_path())
    print("  事件总数 %d · 覆盖 %d 天 · 事件分布 %s" % (len(rows), len(days), dict(by_ev)))
    print("  日流量: %s" % dict(sorted(days.items())))
    if by_spec:
        print("  路由分布: %s" % dict(by_spec.most_common()))
    # 漏斗（V1.9.36 三口径）：agent（会话流·显式）/ manual-test（人工测试·保守默认）/
    # install+reltest（装痕/发版）——route 按 source 分列，entry_read/role_card_read 全局计
    agent_routes = [r for r in rows if r.get("event") == "route" and r.get("source") == "agent"]
    manual_routes = [r for r in rows if r.get("event") == "route" and r.get("source") == "manual-test"]
    r_ = len(agent_routes)
    e_, c_ = by_ev.get("entry_read", 0), by_ev.get("role_card_read", 0)
    installs = by_ev.get("install_selftest", 0)
    print("── 链路漏斗（三口径分列·V1.9.36）──")
    print("  route·agent 会话流 %d ／ route·manual-test 人工测试 %d ／ route·旧口径含混 %d"
          % (r_, len(manual_routes),
             len([r for r in rows if r.get("event") == "route" and r.get("source") not in ("agent", "manual-test", "reltest", "install_selftest")])))
    print("  agent 流漏斗: route %d → entry_read %d → role_card_read %d（装痕 install_selftest %d 条另行）" % (r_, e_, c_, installs))
    if r_:
        print("  路由→入口断裂率 %s%%（%d 次路由后未读入口）" % (round(100 * (r_ - e_) / r_), r_ - e_))
    if e_:
        print("  入口→角色断裂率 %s%%（%d 次读入口后未读卡）" % (round(100 * (e_ - c_) / e_), e_ - c_))
    if r_ and e_ == 0:
        print("  ⚠ 读取层零上报：Agent 未执行 AETC 链路自报协议（与 V1.9.33 前同态）")
    if not r_ and rows:
        print("  ⚠ Agent 会话流 route=0（现有事件全为人工测试/装痕）——调度器在 Agent 侧未被执行（V1.9.36 判定即读改造目标）")
    print("  〔口径声明〕本报告=机器轨诊断，不作为会话真实性证据——真实性以对话证据（黄金测试质询「引用原文」）为准；")
    print("  〔勘误提示〕V1.9.35 前旧数据 source=agent 可能含人工测试混入（如 10-23 00:38 的 11 条批量验证）——判读时对照 ts 秒级聚集特征")
    return 0


def tail(n=20):
    rows = _read()[-n:]
    for r in rows:
        print(json.dumps(r, ensure_ascii=False)[:160])
    return 0


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    if sys.argv[1] == "--report":
        return report()
    if sys.argv[1] == "--tail":
        return tail(int(sys.argv[2]) if len(sys.argv) > 2 else 20)
    if sys.argv[1] == "--event":
        # Agent 读取层自报协议入口（AETC §11.1）：--event <类型> [--spec SDLC] [--role 04] [--file X] [--task Y]
        ev = sys.argv[2] if len(sys.argv) > 2 else ""
        kv = {}
        i = 3
        while i < len(sys.argv) - 1:
            if sys.argv[i].startswith("--"):
                kv[sys.argv[i][2:]] = sys.argv[i + 1]
                i += 2
            else:
                i += 1
        if not ev:
            print("用法：--event <entry_read|role_card_read|gate> [--spec SDLC] [--role 04] ...")
            return 2
        entry = dict({"event": ev}, **{k: v for k, v in kv.items() if v})
        pth = append(entry)
        if pth:
            print("[TRACE] 已记:", pth)
            return 0
        print("[TRACE] 拒写（事件类型不合法或写失败）")
        return 1
    if sys.argv[1] == "--mark-install":
        ver = sys.argv[2] if len(sys.argv) > 2 else "unknown"
        p = append({"event": "install_selftest", "version": ver, "note": "装后自测·装痕显式分家（与 route 真实流量区分）"})
        print("install_selftest 打标:", p or "(失败)")
        return 0 if p else 1
    print(__doc__)
    return 1


if __name__ == "__main__":
    sys.exit(main())
