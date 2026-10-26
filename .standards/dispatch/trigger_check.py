#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""STD-HUB-001 调度面·首轮触发自检（V1.8.21）
正本唯一：触发词从 02_统一调度入口/AGENTS.md 调度表 + SDLC.md 0.2 角色表运行时解析（派生零漂移）。
用法：
  python3 .standards/dispatch/trigger_check.py "任务短语"    # 首条任务时跑
  python3 .standards/dispatch/trigger_check.py --selftest     # 12 短语回归（suite_check 调用）
  python3 .standards/dispatch/trigger_check.py --intent --type A --object B --artifact C [--text 摘要]
      # V1.9.19 意图三答 → 计分路由（INTENT-MATRIX.md）
  python3 .standards/dispatch/trigger_check.py --refine --spec AEGIS --object O --env E --done D --artifact A [--text 原文]
      # V1.9.29 输入规范化：四答 → 必填校验 → 澄清清单或标准重写（REFINE-MATRIX.md）
  ... --read-entry   # V1.9.36 判定-读取绑定（可与 --text/--intent/--refine 组合）：
      # 判定 UNIQUE/READY 时 route + entry_read 同链落 trace——一条命令完成判定+读痕（自报摩擦归零）
输出：命中规范（分组）+ 角色级二级匹配 + 加载指令。退出码：0 命中 / 2 零命中。
  --intent：0 唯一 / 3 低置信或多候选；--refine：0 齐备渲染 / 4 缺失澄清 / 2 未命中矩阵行。
"""
import os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))  # .standards/dispatch/ → 项目根

MUTEX = [("LPU", "LR"), ("SDLC", "AEGIS", "UTSS")]  # 任务型互斥组
ALWAYS = {"AETC"}  # 横切常驻
GATE_HINT = {"CR", "UCSS", "META"}  # 质量门型（时机门，非本轮加载目标）


def strip_notes(t):
    """去括号注释/反引号说明/粗体标记，保留触发词主体。"""
    t = re.sub(r"（[^）]*）", "", t)
    t = re.sub(r"\([^)]*\)", "", t)
    t = t.replace("**", "").replace("`", "")
    return t


def words_of(text):
    """从触发面/角色行文本剥候选词：引号词 + 顿号/斜杠/竖线分隔的短语段。"""
    words = set(re.findall(r"[\"“]([^\"”]{2,14})[\"”]", text))
    for seg in re.split(r"[、/|;；,，]", strip_notes(text)):
        seg = seg.strip()
        if 2 <= len(seg) <= 12 and not re.search(r"[.\s]|^或|^且|^存在|^输入|^明确|^装即|^未", seg):
            words.add(seg)
    return {w for w in words if not w.startswith(".") and "/" not in w}


def dirs_of(text):
    """条件目录（或存在 `.lpu/` 等）。"""
    return re.findall(r"`(\.[\w.-]+/?)`", text)


def load_dispatch():
    """解析 AGENTS.md 调度表（正本）——安装态根平铺优先，工作区原位（03_规范本体/.standards
    → 兄弟 02_统一调度入口/）探测：开发态可直跑 selftest（V1.9.19 顺手补，原仅安装态可跑）。"""
    for p in (os.path.join(ROOT, "AGENTS.md"),
              os.path.join(os.path.dirname(ROOT), "02_统一调度入口", "AGENTS.md")):
        if os.path.isfile(p):
            break
    else:
        return []
    rows = []
    for line in open(p, encoding="utf-8"):
        m = re.match(r"^\|\s*(\d+)\s*\|(.+)\|\s*`([^`]+)`\s*\|", line)
        if not m or "---" in line:
            continue
        num, rest, entry = m.group(1), m.group(2), m.group(3)
        cols = [c.strip() for c in rest.split("|")]
        if len(cols) < 4:
            continue
        name, code = cols[0], cols[1]
        code_key = re.match(r"[A-Z]+", code).group(0)
        typ = strip_notes(cols[2])
        face = cols[3]
        rows.append(dict(num=num, name=name, code=code, key=code_key, typ=typ,
                         face=face, words=words_of(face), dirs=dirs_of(face), entry=entry))
    return rows


def load_roles():
    """解析 SDLC.md 0.2 角色表（编号→名称，用于结果展示）。"""
    p = os.path.join(ROOT, ".standards", "SDLC.md")
    if not os.path.isfile(p):
        return []
    roles = []
    for line in open(p, encoding="utf-8"):
        m = re.match(r"^\|\s*(?:\*\*[^*]+\*\*\s*)?\|\s*(\d{2})\s*\|\s*([^|]+)\|\s*([^|]*)\|", line)
        if not m or "角色编号" in line:
            continue
        roles.append(dict(id=m.group(1), name=m.group(2).strip()))
    return roles


def load_routes():
    """解析 SDLC.md 0.2 路由表（关键词触发列 → 激活角色列）——角色级匹配正本。"""
    p = os.path.join(ROOT, ".standards", "SDLC.md")
    if not os.path.isfile(p):
        return []
    routes = []
    in_route = False
    for line in open(p, encoding="utf-8"):
        if "关键词触发" in line:
            in_route = True
            continue
        if in_route:
            if not line.startswith("|"):
                break
            if re.match(r"^\|[-\s|]+\|$", line):
                continue  # 跳过表分隔行
            m = re.match(r"^\|([^|]+)\|\s*角色\s*(.+?)\s*\|", line)
            if not m:
                break
            words = {w.strip() for w in strip_notes(m.group(1)).split("/") if 2 <= len(w.strip()) <= 14}
            ids = re.findall(r"\d{2}", m.group(2))
            routes.append(dict(words=words, ids=ids, note=strip_notes(m.group(2))))
    return routes


def grams(phrase):
    """短语多粒度 n-gram（词级交集用）。"""
    return {phrase[i:i + n] for n in (2, 3, 4) for i in range(len(phrase) - n + 1)} | {phrase}


def word_hit(word, phrase):
    """双向词级命中：整词在短语中，或短语任一 gram 覆盖词（如'架构'覆盖'架构设计'）。"""
    if word in phrase or phrase in word:
        return True
    return any(g in word for g in grams(phrase))


def match(phrase):
    drows = load_dispatch()
    hits = []
    for r in drows:
        if r["key"] in ALWAYS:
            continue  # 常驻：装即生效，不做短语匹配
        hit_words = [w for w in r["words"] if w in phrase]  # 规范级=严格整词子串（防长解释词误命中）
        dhit = any(os.path.exists(os.path.join(os.getcwd(), d.rstrip("/"))) for d in r["dirs"])  # V1.9.0：条件目录按 cwd（项目根）探测——P2 全局形态下规范根≠项目根
        if hit_words or dhit:
            r = dict(r, why=hit_words[:3],
                     cond=[d for d in r["dirs"] if os.path.exists(os.path.join(os.getcwd(), d.rstrip("/")))])
            hits.append(r)
    return drows, hits


def match_routes(phrase):
    """角色级：路由表词级命中 → 去重角色编号集（含触发词）。"""
    got = {}
    for rt in load_routes():
        for w in rt["words"]:
            if word_hit(w, phrase):
                for rid in rt["ids"]:
                    got.setdefault(rid, w)
                break
    return got


def render(phrase, drows, hits):
    out = ["[TRIGGER-CHECK] 输入：" + phrase]
    if not hits:
        out.append("  零命中——按调度器兜底：明确开发任务走 SDLC（.standards/SDLC.md 0.2 表逐角色核对）；")
        out.append("  仍无法归类时按 AETC 常驻留痕先行，任务澄清后复跑本脚本。")
        out.append("[TRIGGER-CHECK] Step 0：复跑 `python3 .standards/dispatch/trigger_check.py \"澄清后的短语\"`")
        return "\n".join(out), 2
    load_now, gates = [], []
    for h in hits:
        (gates if h["key"] in GATE_HINT else load_now).append(h)
    out.append("命中规范（本轮加载）：")
    rnames = {r["id"]: r["name"] for r in load_roles()}
    sdlc_hit = any(h["key"] == "SDLC" for h in load_now)
    for h in sorted(load_now, key=lambda x: int(x["num"])):
        why = "、".join(h["why"]) if h["why"] else "条件目录：" + "、".join(h["cond"])
        out.append(f"  ✓ {h['name']}（{h['code']}·{h['typ']}）→ 入口 {h['entry']}　[触发：{why}]")
        if h["key"] == "SDLC" and sdlc_hit:
            rhits = match_routes(phrase)
            for rid, w in sorted(rhits.items()):
                nm = rnames.get(rid, "")
                out.append(f"      角色级：{rid} {nm} → .standards/roles/（{rid} 号卡；触发词：{w}；三级加载 §8.2：卡→技能→模板）")
    if gates:
        out.append("时机门（产出后/合入前执行，非本轮加载）：" +
                   "、".join(f"{g['name']}（{g['code']}）" for g in gates))
    out.append("横切常驻（装即生效）：AETC 留痕——一切写步骤按 R 分级留痕/CKP-002A。")
    conflicts = []
    for group in MUTEX:
        both = [h["key"] for h in load_now if h["key"] in group]
        if len(both) > 1:
            conflicts.append("互斥组 [" + "/".join(both) + "] 多命中——按任务主目标择一加载（理解→LPU、实施→LR；修复域→AEGIS、测试域→UTSS、开发→SDLC）")
    for c in conflicts:
        out.append("⚠ " + c)
    out.append("[TRIGGER-CHECK] Step 0：先读命中入口，再按其加载协议执行；禁止一次性读入全部规范正文。")
    return "\n".join(out), 0


SELFTEST = [  # （短语, 期望规范级 key 集合, 期望角色级编号集合）
    ("设计电商系统技术架构", {"SDLC"}, {"03"}),
    ("写SaaS用户手册", {"SDLC"}, {"12"}),
    ("做性能压测方案", {"SDLC"}, {"06"}),
    ("分析客户续约风险", {"SDLC"}, {"14"}),
    ("设计登录页界面", {"SDLC", "UI"}, {"11"}),
    ("代码重构", {"LPU", "LR"}, set()),
    ("排查生产环境故障", {"OPS"}, set()),
    ("A/B实验放量决策", {"SDLC"}, {"13"}),
    ("写API接口文档", {"SDLC"}, {"12"}),
    ("数据库表结构变更", {"SDLC", "CODING"}, {"04"}),
    ("政策影响分析", {"SDLC"}, {"00"}),
    ("界面改版视觉回归验证", {"SDLC", "UI"}, {"06"}),
    ("验证外源分支登录功能", {"UTSS"}, set()),
]

# ─────────────── V1.9.19 意图语义路由（INTENT-MATRIX.md 派生零漂移） ───────────────
def load_intent():
    """解析 INTENT-MATRIX.md（语义正本）——多路径探测：
    安装态（根平铺）/ 工作区原位（03_规范本体/.standards → 兄弟 02_统一调度入口）。"""
    for p in (os.path.join(ROOT, "INTENT-MATRIX.md"),
              os.path.join(os.path.dirname(ROOT), "02_统一调度入口", "INTENT-MATRIX.md"),
              os.path.join(ROOT, "02_统一调度入口", "INTENT-MATRIX.md")):
        if not os.path.isfile(p):
            continue
        rows = []
        for line in open(p, encoding="utf-8"):
            m = re.match(r"^\|\s*(\d+)\s*\|\s*([^|]+)\|([^|]*)\|([^|]*)\|([^|]*)\|([^|]*)\|([^|]*)\|", line)
            if not m or "规范" in line and m.group(1) == "#":
                continue
            if "---" in line:
                continue
            num, name, typ, obj, art, feat, sample = (m.group(i).strip() for i in range(1, 8))
            if not typ or not obj:
                continue
            rows.append(dict(
                num=num, name=name, key=re.match(r"[A-Z]+", name).group(0) if re.match(r"[A-Z]+", name) else name,
                types={w.strip() for w in strip_notes(typ).replace("（", "/").replace("）", "/").split("/") if 2 <= len(w.strip()) <= 12},
                objs={w.strip() for w in re.split(r"[/，, ]", strip_notes(obj)) if 2 <= len(w.strip()) <= 14},
                arts={w.strip() for w in re.split(r"[/，, ]", strip_notes(art)) if 2 <= len(w.strip()) <= 14},
                feats={w.strip() for w in re.split(r"[,，、]", strip_notes(feat)) if 2 <= len(w.strip()) <= 14},
                samples=[s.strip("「」 ") for s in re.findall(r"「([^」]+)」", sample) if s.strip()],
            ))
        if rows:
            return rows
    return []


def intent_match(t="", o="", a="", raw=""):
    """意图三答 → 计分映射：type×3 / object×2 / artifact×2 / 语义特征词×1。
    判定：top1≥3 且领先 top2≥2 → 唯一推荐；0 分 → 低置信；其余 top3。"""
    rows, scored = load_intent(), []
    blob = " ".join(x for x in (t, o, a, raw) if x)
    for r in rows:
        s = 3 * sum(1 for w in r["types"] if w and w in blob) \
            + 2 * sum(1 for w in r["objs"] if w and w in blob) \
            + 2 * sum(1 for w in r["arts"] if w and w in blob) \
            + 1 * sum(1 for w in r["feats"] if w and w in raw)
        if s > 0:
            scored.append((s, r))
    scored.sort(key=lambda x: (-x[0], int(x[1]["num"])))
    top1 = scored[0] if scored else None
    top2 = scored[1] if len(scored) > 1 else None
    if top1 is None:
        return "LOW", [], scored[:3]
    # V1.9.33 判定线两档：≥3 分且领先 ≥2 → UNIQUE（强）；
    # ≥2 分且（单行或领先 ≥1）→ UNIQUE（弱——原文直连通道：objs/arts×2 重权与 feats×1
    # 弱信号天然分层，2:1 的语义已清晰；三答通道因 type×3 高分不受此档影响）
    if top1[0] >= 3 and (top2 is None or top1[0] - top2[0] >= 2):
        return "UNIQUE", [top1], scored[:3]
    if top1[0] >= 2 and (top2 is None or top1[0] - top2[0] >= 1):
        return "UNIQUE", [top1], scored[:3]
    return "TOP3", scored[:3], scored[:3]


# ─────────────── V1.9.20 协作链推荐（INTEROP-MATRIX.md 派生零漂移） ───────────────
def load_chains():
    """解析 INTEROP-MATRIX.md §四机读链表——路径探测同 load_intent 双布局。"""
    for p in (os.path.join(ROOT, "INTEROP-MATRIX.md"),
              os.path.join(os.path.dirname(ROOT), "02_统一调度入口", "INTEROP-MATRIX.md")):
        if not os.path.isfile(p):
            continue
        chains = []
        for line in open(p, encoding="utf-8"):
            m = re.match(r"^\|([^|]+)\|\s*([A-Z]+)\s*\|([^|]*)\|([^|]*)\|([^|]*)\|\s*$", line)
            if not m or "---" in line or "主入口" in line:
                continue
            name, key, stack, gates, arts = (x.strip() for x in m.groups())
            chains.append(dict(name=name, key=key,
                               stack=[s.strip() for s in stack.split(",") if s.strip()],
                               gates=[s.strip() for s in gates.split(";") if s.strip()],
                               arts=[s.strip() for s in arts.split(";") if s.strip()]))
        if chains:
            return chains
    return []


CHAIN_SELFTEST = [  # （三答标注, 期望主入口 key, 期望链名, 期望叠加集⊇, 期望顺序门要素⊇）
    (("修复", "缺陷 报错 工单", "修复后代码 三单"), "AEGIS", "链A修复",
     {"CODING", "AETC"}, {"CR", "UCSS", "UTSS"}),
    (("开发全流程", "新功能 产品", "交付物 PRD"), "SDLC", "链B开发",
     {"CODING", "AETC"}, {"CR", "UCSS", "UTSS"}),
    (("理解分析", "存量项目 没文档", "理解报告 rebuild-plan"), "LPU", "链C重构",
     {"CODING", "AETC"}, {"rebuild-plan", "CR", "UCSS", "UTSS"}),
    (("故障响应", "线上服务 事件", "应急处置 复盘报告"), "OPS", "链D运维",
     {"AETC"}, {"双门"}),
]


# ─────────────── V1.9.29 输入规范化（REFINE-MATRIX.md 派生零漂移） ───────────────
REFINE_FIELDS = ("object", "env", "done", "artifact")

REFINE_ASK = {  # 澄清问句模板（按缺失要素渲染·{domain} 取矩阵行域词）
    "object": "要说清{domain}具体指哪个（文件/服务/模块/文档名）",
    "env": "要说清运行环境（本地/测试/线上·项目根与前置条件）",
    "done": "要说清做到什么程度算完成（验证/回归/交付标准）",
    "artifact": "要说清期望拿到什么产物（代码/报告/方案/结论）",
}


def load_refine():
    """解析 REFINE-MATRIX.md（输入规范化正本）——路径探测同 load_intent 双布局：
    安装态（根平铺）/ 工作区原位（03_规范本体/.standards → 兄弟 02_统一调度入口）。"""
    for p in (os.path.join(ROOT, "REFINE-MATRIX.md"),
              os.path.join(os.path.dirname(ROOT), "02_统一调度入口", "REFINE-MATRIX.md")):
        if not os.path.isfile(p):
            continue
        rows = []
        for line in open(p, encoding="utf-8"):
            m = re.match(r"^\|\s*(\d+)\s*\|\s*([A-Z]+)\s*\|([^|]*)\|([^|]*)\|([^|]*)\|([^|]*)\|\s*$", line)
            if not m or "---" in line or m.group(2) == "规范":
                continue
            num, key, req, domain, sample, expect = (m.group(i).strip() for i in range(1, 7))
            if not req:
                continue
            rows.append(dict(
                num=num, key=key,
                required={w.strip() for w in req.split(",") if w.strip() in REFINE_FIELDS},
                domain=domain, sample=sample.strip("「」 "), expect=expect,
            ))
        if rows:
            return rows
    return []


def refine_check(spec, answers):
    """四答 → 必填校验 → 澄清清单或标准重写（V1.9.29·Step 0b/0c 运行面）。
    返回 (missing, rendered, row)：missing=缺失要素列表（空=齐备）；
    齐备时 rendered=标准重写句（统一骨架按要素非空渲染）；row=None 表示 spec 未命中矩阵。"""
    row = next((r for r in load_refine() if r["key"] == spec or r["num"] == str(spec)), None)
    if row is None:
        return None, None, None
    missing = [f for f in REFINE_FIELDS if f in row["required"] and not answers.get(f, "").strip()]
    if missing:
        return missing, None, row
    segs = []
    for f, label in (("object", "对象"), ("env", "环境"), ("done", "完成标准"), ("artifact", "期望产物")):
        v = answers.get(f, "").strip()
        if v:
            segs.append(f"{label}：{v}")
    rendered = f"{row['domain']}请求（{row['key']}）｜" + "｜".join(segs)
    return [], rendered, row


REFINE_SELFTEST = [  # （规范 spec, 四答标注, 期望）——混乱输入判定回归锚（V1.9.29）
    # 期望格式：("missing", "object,env,done") 精确缺集；("render", "段1|段2") 渲染必含段
    ("AEGIS", dict(object="", env="", done="", artifact="修复后代码"),
     ("missing", "object,env,done")),
    ("OPS", dict(object="", env="", done="给出根因", artifact=""),
     ("missing", "object,env")),
    ("SDLC", dict(object="", env="", done="", artifact="交付物"),
     ("missing", "object,done")),
    ("CR", dict(object="", env="", done="", artifact=""),
     ("missing", "object,done")),
    ("AEGIS", dict(object="登录接口报错", env="测试环境登录服务",
                   done="回归通过+三单留痕", artifact="修复后代码"),
     ("render", "对象：登录接口报错|环境：测试环境登录服务|完成标准：回归通过+三单留痕|期望产物：修复后代码")),
]



try:
    import trace_log as _trace   # V1.9.34 链路监控：route 事件自动写 trace-log.jsonl
except ImportError:
    _trace = None

READ_ENTRY = "--read-entry" in sys.argv  # V1.9.36 判定-读取绑定：命令行带此 flag 即声明已读入口


def trace_route(method, extra):
    """路由事件落 trace-log（机器强制层）。失败不阻塞路由主流程。
    V1.9.36 判定-读取绑定：READ_ENTRY 且判定 UNIQUE/READY → 追加 entry_read 同链
    （route+读痕一条命令落齐——Agent 自报摩擦归零，判型：黄金测试「自报而未读」）。"""
    if _trace is None:
        return
    try:
        _trace.append(dict({"event": "route", "method": method}, **extra))
        if READ_ENTRY and extra.get("verdict") in ("UNIQUE", "READY") and extra.get("spec"):
            _trace.append({"event": "entry_read", "spec": extra["spec"],
                           "via": method, "note": "判定-读取同链（--read-entry·V1.9.36）"})
    except Exception:
        pass

def _proj_root():
    """落痕/探测的项目根锚定（V1.9.33 修复：cwd 相对拼接曾致双 .standards 错位）。
    优先级：P1 安装形态（ROOT 根有 AGENTS.md）→ 开发仓形态（兄弟 02_统一调度入口）
    → P2 全局形态（cwd=项目根，与条件目录探测同语义）。"""
    if os.path.isfile(os.path.join(ROOT, "AGENTS.md")):
        return ROOT
    if os.path.isdir(os.path.join(os.path.dirname(ROOT), "02_统一调度入口")):
        return ROOT
    return os.getcwd()


def intent_log(entry):
    try:
        d = os.path.join(_proj_root(), ".standards", "audit")
        os.makedirs(d, exist_ok=True)
        p = os.path.join(d, "intent-log.jsonl")
        import json as _json
        with open(p, "a", encoding="utf-8") as f:
            f.write(_json.dumps(entry, ensure_ascii=False) + "\n")
        return p
    except OSError:
        return None


INTENT_SELFTEST = [  # （无触发词例句, 意图三答标注, 期望唯一规范 key）——语义路由补词面盲区的回归锚
    ("这个项目接手仨月了还是不敢动，一改就崩", ("理解存量项目", "旧系统 没文档", "理解报告 摸底结论"), "LPU"),
    ("凌晨三点服务挂了，用户在投诉", ("故障响应", "线上服务 告警事件", "应急处置 复盘报告"), "OPS"),
    ("这段代码一跑就崩，帮我看看", ("修复", "缺陷 报错", "修复后代码"), "AEGIS"),
    ("上线前帮我查一下有没有什么安全隐患", ("扫描", "代码 依赖", "扫描报告 漏洞清单"), "UCSS"),
    ("改完了，怎么确认没改坏", ("验证", "改动后的功能", "验证结论 回归报告"), "UTSS"),
    ("这个页面看着太乱了，帮我理一下", ("界面设计", "页面 布局", "界面方案"), "UI"),
    ("这个东西该做成规范还是技能", ("治理咨询", "规范 技能 体系", "判型记录 治理结论"), "META"),
    ("我要做一个会员积分功能，从需求到上线", ("开发全流程", "新功能 产品", "交付物"), "SDLC"),
]


def selftest():
    fails = []
    for phrase, exp_keys, exp_roles in SELFTEST:
        drows, hits = match(phrase)
        got_keys = {h["key"] for h in hits} - ALWAYS
        got_roles = set(match_routes(phrase)) if any(h["key"] == "SDLC" for h in hits) else set()
        miss_k = exp_keys - got_keys
        miss_r = exp_roles - got_roles
        status = "✓" if not miss_k and not miss_r else "✗"
        if status == "✗":
            fails.append(f"{phrase}：缺规范{miss_k or '∅'} 缺角色{miss_r or '∅'}（实得 {got_keys}|{got_roles}）")
        print(f"  {status} {phrase} → 规范 {sorted(got_keys) or '∅'} 角色 {sorted(got_roles) or '∅'}")
    print(f"selftest：{len(SELFTEST) - len(fails)}/{len(SELFTEST)} 通过")
    # V1.9.19 意图语义路由回归：无触发词例句 → 三答计分 → 唯一命中期望规范
    intent_fails = []
    irows = load_intent()
    if len(irows) < 11:
        intent_fails.append(f"INTENT-MATRIX 解析行数 {len(irows)} < 11（正本缺失或列漂移）")
    for sample, (t, o, a), exp in INTENT_SELFTEST:
        verdict, tops, _ = intent_match(t, o, a, sample)
        got = tops[0][1]["key"] if tops else "∅"
        ok = verdict == "UNIQUE" and got == exp
        if not ok:
            intent_fails.append(f"{sample}：期望 {exp} 唯一命中，实得 {verdict} {got}")
        print(f"  {'✓' if ok else '✗'}[intent] {sample} → {got}（{verdict}）")
    fails += intent_fails
    # V1.9.20 协作链回归：三答→主入口唯一命中→链推荐（叠加⊇+顺序门⊇断言）
    chain_fails = []
    chains = load_chains()
    if len(chains) < 5:
        chain_fails.append(f"INTEROP-MATRIX 机读链表解析 {len(chains)} < 5（正本缺失或格式漂移）")
    for (t, o, a), exp_key, exp_chain, exp_stack, exp_gates in CHAIN_SELFTEST:
        verdict, tops, _ = intent_match(t, o, a, "")
        ckey = tops[0][1]["key"] if tops else "∅"
        ch = next((c for c in chains if c["key"] == ckey), None)
        stack_blob = " ".join(ch["stack"]) if ch else ""
        gates_blob = " ".join(ch["gates"]) if ch else ""
        ok = (verdict == "UNIQUE" and ckey == exp_key and ch
              and ch["name"] == exp_chain
              and all(w in stack_blob for w in exp_stack)
              and all(g in gates_blob for g in exp_gates))
        if not ok:
            chain_fails.append(f"{exp_chain}：期望主入口 {exp_key}+{exp_chain}，实得 {ckey} {ch['name'] if ch else '无链'}（stack={stack_blob} gates={gates_blob}）")
        print(f"  {'✓' if ok else '✗'}[chain] {exp_chain} → 主入口 {ckey}·叠加 {stack_blob or '∅'}·门 {gates_blob or '∅'}")
    fails += chain_fails
    # V1.9.29 输入规范化回归：混乱输入四答 → 必填校验 → 澄清/渲染（4 澄清 + 1 齐备对照防过度澄清回缩）
    refine_fails = []
    rrows = load_refine()
    if len(rrows) < 12:
        refine_fails.append(f"REFINE-MATRIX 解析行数 {len(rrows)} < 12（正本缺失或列漂移）")
    for spec, ans, (kind, exp) in REFINE_SELFTEST:
        missing, rendered, row = refine_check(spec, ans)
        if row is None:
            refine_fails.append(f"{spec}：矩阵行未命中（REFINE-MATRIX 缺该规范行）")
            continue
        if kind == "missing":
            ok = missing and ",".join(missing) == exp
        else:
            ok = (not missing) and rendered and all(w in rendered for w in exp.split("|"))
        if not ok:
            refine_fails.append(f"{spec}：期望 {kind} {exp}，实得 missing={','.join(missing) or '∅'} rendered={rendered or '∅'}")
        label = "缺 " + ",".join(missing) if missing else "渲染：" + (rendered or "∅")[:44] + "…"
        print(f"  {'✓' if ok else '✗'}[refine] {spec}（{row['domain']}）→ {label}")
    fails += refine_fails
    print(f"selftest：词面 {len(SELFTEST) - len([f for f in fails if '缺规范' in f])}/{len(SELFTEST)}"
          f" + 意图 {len(INTENT_SELFTEST) - len(intent_fails)}/{len(INTENT_SELFTEST)}"
          f" + 链 {len(CHAIN_SELFTEST) - len(chain_fails)}/{len(CHAIN_SELFTEST)}"
          f" + 规范化 {len(REFINE_SELFTEST) - len(refine_fails)}/{len(REFINE_SELFTEST)} 通过")
    return 1 if fails else 0


if __name__ == "__main__":
    if "--read-entry" in sys.argv:  # V1.9.36 剥离 flag（防 --intent/--refine 的 kv 解析吞值）
        sys.argv = [a for a in sys.argv if a != "--read-entry"]
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    if sys.argv[1] == "--selftest":
        sys.exit(selftest())
    if sys.argv[1] == "--text":
        # V1.9.33 自由文本直连入口：用户原文直接进——L2 语义计分（特征词×1）
        # 语义层 UNIQUE → 直接给路由指令；LOW → 澄清协议（禁止瞎路由）；
        # 模型解构从「必经之路」降级为「可选加速」——入口不再依赖三答先行。
        raw = sys.argv[2] if len(sys.argv) > 2 else ""
        if not raw:
            print("用法：--text \"用户输入原文\"")
            sys.exit(2)
        verdict, tops, cand = intent_match(raw=raw)
        print("[TEXT-CHECK] 原文：%s" % raw[:80])
        if verdict == "UNIQUE":
            s, r = tops[0]
            print(f"唯一推荐：{r['name']}（{r['key']}·#{r['num']}，语义得分 {s}）→ 读 {r['key']} 入口再动工")
            if r["key"] == "SDLC":
                print("  [SDLC 直连] 建造类任务命中——按 AGENTS.md 主表读 .standards/SDLC.md 并激活对应角色卡（roles/ 首部三十秒版）")
            if r["key"] == "CODING":
                print("  [链 B 开发链] 单点编码任务——编码全程叠加 CODING 红线；功能级开发先激活 SDLC 04 开发角色卡（roles/04-*.md 三十秒版）")
            ch = next((c for c in load_chains() if c["key"] == r["key"]), None)
            if ch:
                print(f"  [协作链] {ch['name']}：叠加 {'+'.join(ch['stack'])}｜顺序门 {' → '.join(ch['gates'])}")
        elif verdict == "LOW":
            print("低置信（语义层 0 分）——按澄清协议反问任务类型/对象/产物，禁止瞎路由。")
            print("  （或补三答后跑 --intent 提高判型精度）")
        else:
            print("多候选（top3）——按任务主目标+互斥裁决择一，或补三答后跑 --intent：")
            for s, r in cand:
                print(f"  {r['name']}（{r['key']}·得分 {s}）")
        logp = intent_log({"ts": __import__("datetime").datetime.now().isoformat(timespec="seconds"),
                           "record_type": "text", "text": raw[:80],
                           "verdict": verdict, "top": [r["key"] for _, r in tops],
                           "cands": [r["key"] for _, r in cand]})
        trace_route("--text", {"input": raw[:80], "verdict": verdict,
                               "spec": tops[0][1]["key"] if tops else "",
                               "top": [r["key"] for _, r in tops]})
        if logp:
            print(f"[TEXT-CHECK] 落痕：{logp}（原文路由可复盘）")
        if verdict == "UNIQUE" and not READ_ENTRY:
            print("[行动尾·V1.9.36] 判定说出口=读取已完成——已读入口？加 --read-entry 重跑，"
                  "route+entry_read 一条命令同链落痕（说而不读=违例）")
        sys.exit(0 if verdict == "UNIQUE" else 3)
    if sys.argv[1] == "--intent":
        # 用法：--intent --type 任务类型 --object 对象 --artifact 产物 [--text 用户输入摘要]
        kv = {}
        i = 2
        while i < len(sys.argv) - 1:
            if sys.argv[i].startswith("--"):
                kv[sys.argv[i][2:]] = sys.argv[i + 1]
                i += 2
            else:
                i += 1
        t, o, a, raw = kv.get("type", ""), kv.get("object", ""), kv.get("artifact", ""), kv.get("text", "")
        verdict, tops, cand = intent_match(t, o, a, raw)
        print("[INTENT-CHECK] 三答：type=%s｜object=%s｜artifact=%s" % (t, o, a))
        if verdict == "UNIQUE":
            s, r = tops[0]
            print(f"唯一推荐：{r['name']}（{r['key']}·#{r['num']}，得分 {s}）→ 按 AGENTS.md 主表加载其入口")
            if r["key"] in ("CR", "UCSS"):
                print("  （时机门提示：产出后/合入前执行——CR 与 UCSS 双门并行去重）")
            ch = next((c for c in load_chains() if c["key"] == r["key"]), None)
            if ch:
                print(f"  [协作链] {ch['name']}（V1.9.20）:")
                print(f"    叠加：{'+'.join(ch['stack'])}（与主入口并行生效）")
                print(f"    顺序门：{' → '.join(ch['gates'])}")
                print(f"    交接物：{'；'.join(ch['arts'])}")
                print("    （全要素协议见 INTEROP-MATRIX.md——链式激活无需翻散落文档）")
        elif verdict == "LOW":
            print("低置信（0 分）——按澄清协议：向用户反问任务类型/对象/产物，禁止瞎路由。")
        else:
            print("多候选（top3）：")
            for s, r in cand:
                print(f"  {r['name']}（{r['key']}·得分 {s}）")
            print("  按任务主目标+互斥裁决择一（模型结合上下文），裁决结果照落痕。")
        logp = intent_log({"ts": __import__("datetime").datetime.now().isoformat(timespec="seconds"),
                           "text": raw[:80], "type": t, "object": o, "artifact": a,
                           "verdict": verdict, "top": [r["key"] for _, r in tops],
                           "cands": [r["key"] for _, r in cand]})
        trace_route("--intent", {"input": raw[:80] or o[:80], "verdict": verdict,
                                 "spec": tops[0][1]["key"] if tops else "",
                                 "top": [r["key"] for _, r in tops]})
        if logp:
            print(f"[INTENT-CHECK] 落痕：{logp}（意图判定可复盘）")
        sys.exit(0 if verdict == "UNIQUE" else 3)
    if sys.argv[1] == "--refine":
        # V1.9.29 用法：--refine --spec 规范KEY --object O --env E --done D --artifact A [--text 用户原文]
        kv = {}
        i = 2
        while i < len(sys.argv) - 1:
            if sys.argv[i].startswith("--"):
                kv[sys.argv[i][2:]] = sys.argv[i + 1]
                i += 2
            else:
                i += 1
        spec = kv.get("spec", "")
        ans = {f: kv.get(f, "") for f in REFINE_FIELDS}
        missing, rendered, row = refine_check(spec, ans)
        if row is None:
            print(f"[REFINE-CHECK] 未命中矩阵行：spec={spec}（--spec 取规范 KEY 如 AEGIS，或矩阵行号）")
            sys.exit(2)
        print("[REFINE-CHECK] 规范：%s（%s·#%s）｜必填要素：%s" % (
            row["key"], row["domain"], row["num"], ",".join(f for f in REFINE_FIELDS if f in row["required"]) or "无"))
        if missing:
            print(f"要素缺失（%d 项）→ 澄清问句优先，禁止瞎补（V1.9.29 低质量协议·对齐低置信禁瞎路由）：" % len(missing))
            for f in missing:
                print(f"  ？{REFINE_ASK[f].format(domain=row['domain'])}")
            print("  （补齐后重跑 --refine；齐备才进入 Step 0c 标准重写——不脑补用户没说的要素）")
            verdict = "MISSING"
        else:
            print("四要素齐备 → 标准重写（Step 0c·双文转发主文）：")
            print(f"  「{rendered}」")
            print("  （原文作附注段随行转发——保语感边角信息，压模型执行方差）")
            verdict = "READY"
        trace_route("--refine", {"spec": row["key"], "verdict": verdict,
                                  "missing": ",".join(missing) if missing else ""})
        logp = intent_log({"ts": __import__("datetime").datetime.now().isoformat(timespec="seconds"),
                           "record_type": "refine", "spec": row["key"], "text": kv.get("text", "")[:80],
                           "object": ans["object"], "env": ans["env"], "done": ans["done"],
                           "artifact": ans["artifact"], "verdict": verdict,
                           "missing": missing, "rendered": rendered})
        if logp:
            print(f"[REFINE-CHECK] 落痕：{logp}（record_type=refine·可复盘）")
        sys.exit(0 if verdict == "READY" else 4)
    phrase = sys.argv[1]
    drows, hits = match(phrase)
    text, code = render(phrase, drows, hits)
    print(text)
    sys.exit(code)
