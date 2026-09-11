#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
格物平台基准评测跑批器（T5.5/S7：本地部署 A/B 验证执行方案）

用法:
  python3 run-benchmark.py --base-url http://localhost:8080/api \
      --user <账号> --password <密码> --model qwen-plus \
      --engine-groups legacy,wenshi,route_on --repeat 2

流程:
  1. 登录取 token
  2. 按分组自动创建评测 Agent（modelConfig.experimentGroup 打标，同名复用）
  3. 逐组逐题调用 POST /v1/ai/chat（engineOverride/modelRouteEnabled 请求级变量）
  4. 每题查最新 executionId -> POST /v1/evaluations/judge 评分
  5. 输出明细 results-<ts>.json 并拉取 /v1/evaluations/experiment-compare 报表

说明:
  - 仅依赖 Python3 标准库
  - 网关限流 100 次/60s/用户，默认每次请求间隔 0.8s
  - 结论判读: docs/design/43 预承诺规则（Δ质量>=+5% 且 Δ成本<=+20% 接线）
"""
import argparse
import json
import sys
import time
import urllib.request
import uuid
from datetime import datetime

# ==================== HTTP 基础 ====================

TOKEN = {"value": None}


def http(method, path, body=None, token=None, timeout=180):
    url = ARGS.base_url.rstrip("/") + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    if body is not None:
        req.add_header("Content-Type", "application/json")
    t = token or TOKEN["value"]
    if t:
        req.add_header("Authorization", f"Bearer {t}")
    req.add_header("Accept", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:
            return json.loads(res.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")[:300]
        raise RuntimeError(f"HTTP {e.code} {method} {path}: {body}") from None


def api(method, path, body=None, timeout=180):
    """带信封解包与限流间隔的 API 调用"""
    time.sleep(ARGS.sleep)
    res = http(method, path, body)
    if not res or res.get("code") != 10000:
        raise RuntimeError(f"{method} {path} 失败: {res}")
    return res["data"]


# ==================== 评测流程 ====================

def login():
    data = api("POST", "/v1/auth/login",
               {"username": ARGS.user, "password": ARGS.password}, timeout=30)
    TOKEN["value"] = data["accessToken"]
    print(f"[1/5] 登录成功: user={ARGS.user}")


def ensure_agents(groups):
    """按分组创建/复用评测 Agent（同名的复用，保证多次跑批数据连续）"""
    existing = {}
    page = api("GET", "/v1/agents?page=1&size=100")
    for a in (page.get("records") or []):
        existing[a.get("agentName")] = a.get("agentId")

    agents = {}
    print("[2/5] 评测 Agent 准备:")
    for group in groups:
        name = f"bench-{group}"
        if name in existing:
            agents[group] = existing[name]
            print(f"  - {name}: 复用 agentId={agents[group]}")
            continue
        model_config = json.dumps({"experimentGroup": group})
        dto = api("POST", "/v1/agents", {
            "agentName": name,
            "description": f"基准评测 Agent（分组={group}，引擎={ARGS.engine_map.get(group, 'legacy')}）",
            "modelProvider": ARGS.provider,
            "modelName": ARGS.model,
            "modelConfig": model_config,
        })
        agents[group] = dto["agentId"]
        print(f"  - {name}: 新建 agentId={agents[group]}")
    return agents


def run_group(group, agent_id, questions, repeat):
    rows = []
    engine = ARGS.engine_map.get(group, "legacy")
    route = group == "route_on"
    for q in questions:
        for r in range(1, repeat + 1):
            label = f"[3/5] {group} #{q['id']} r{r}"
            t0 = time.time()
            try:
                body = {
                    "message": q["question"],
                    "model": ARGS.model,
                    "agentId": agent_id,
                    "engineOverride": engine,
                    "modelRouteEnabled": route,
                    "clientId": str(uuid.uuid4()),
                }
                chat = api("POST", "/v1/ai/chat", body, timeout=300)
                duration_ms = int((time.time() - t0) * 1000)
                answer = chat.get("content") or ""

                # 查最新执行记录取 executionId（同步链路返回前已落库）
                time.sleep(ARGS.sleep)
                page = api("GET", f"/v1/agents/executions/agent/{agent_id}?page=1&size=1")
                records = page.get("records") or []
                execution_id = records[0]["executionId"] if records else None

                # LLM-as-Judge 评分
                score = None
                verdict = None
                if execution_id and ARGS.judge:
                    jr = api("POST", "/v1/evaluations/judge", {
                        "executionId": execution_id,
                        "scenario": q["category"],
                        "acceptanceCriteria": q["acceptanceCriteria"],
                        "output": answer,
                    }, timeout=300)
                    score = jr.get("score")
                    verdict = jr.get("verdict")

                rows.append({
                    "group": group, "qid": q["id"], "category": q["category"],
                    "repeat": r, "executionId": execution_id,
                    "durationMs": duration_ms, "answerLen": len(answer),
                    "score": score, "verdict": verdict,
                })
                print(f"  {label}: {duration_ms}ms score={score}")
            except Exception as e:  # noqa: BLE001
                rows.append({"group": group, "qid": q["id"], "repeat": r,
                             "error": str(e)[:300]})
                print(f"  {label}: 失败 {str(e)[:120]}")
    return rows


def main():
    global ARGS
    parser = argparse.ArgumentParser(description="格物平台基准评测跑批器")
    parser.add_argument("--base-url", default="http://localhost:8080/api",
                        help="后端地址（需含 /api 前缀，Controller 映射为 /api/v1/**）")
    parser.add_argument("--user", required=True)
    parser.add_argument("--password", required=True)
    parser.add_argument("--model", default="qwen-plus")
    parser.add_argument("--provider", default="qwen")
    parser.add_argument("--engine-groups", default="legacy,wenshi,route_on",
                        help="分组: legacy / wenshi / route_on（逗号分隔）")
    parser.add_argument("--repeat", type=int, default=2, help="每题重复轮数")
    parser.add_argument("--question-set", default=None, help="题集 JSON 路径（默认同目录 question-set.json）")
    parser.add_argument("--sleep", type=float, default=0.8, help="请求间隔秒（限流保护）")
    parser.add_argument("--no-judge", action="store_true", help="跳过 LLM-as-Judge 评分")
    ARGS = parser.parse_args()

    ARGS.engine_map = {"legacy": "legacy", "wenshi": "wenshi", "route_on": "legacy"}
    qs_path = ARGS.question_set or __file__.rsplit("/", 1)[0] + "/question-set.json"
    with open(qs_path, encoding="utf-8") as f:
        question_set = json.load(f)
    questions = question_set["questions"]

    print(f"[0/5] 题集 {len(questions)} 题 | 分组 {ARGS.engine_groups} | 每组重复 {ARGS.repeat} 次")
    groups = [g.strip() for g in ARGS.engine_groups.split(",") if g.strip()]

    login()
    agents = ensure_agents(groups)
    all_rows = []
    for group in groups:
        all_rows.extend(run_group(group, agents[group], questions, ARGS.repeat))

    print("[4/5] 拉取实验对比报表...")
    time.sleep(ARGS.sleep)
    try:
        compare = http("GET", "/v1/evaluations/experiment-compare")
        report = compare.get("data")
    except Exception as e:  # noqa: BLE001
        report = {"error": str(e)}

    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    out = f"{__file__.rsplit('/', 1)[0]}/results-{stamp}.json"
    with open(out, "w", encoding="utf-8") as f:
        json.dump({"rows": all_rows, "compare": report}, f, ensure_ascii=False, indent=2)

    print("[5/5] 完成")
    print(f"明细与报表: {out}")
    print("\n== 分组汇总（行数/评分均值/平均时长）==")
    summary = {}
    for row in all_rows:
        if "error" in row:
            continue
        s = summary.setdefault(row["group"], {"n": 0, "scores": [], "durations": []})
        s["n"] += 1
        if row.get("score") is not None:
            s["scores"].append(row["score"])
        if row.get("durationMs") is not None:
            s["durations"].append(row["durationMs"])
    for g, s in sorted(summary.items()):
        avg_score = sum(s["scores"]) / len(s["scores"]) if s["scores"] else None
        avg_dur = sum(s["durations"]) / len(s["durations"]) if s["durations"] else None
        score_str = f"{avg_score:.3f}" if avg_score is not None else "N/A"
        dur_str = f"{avg_dur:.0f}ms" if avg_dur is not None else "N/A"
        print(f"  {g:12s} n={s['n']:3d} avgScore={score_str} avgDuration={dur_str}")
    print("\n判读: docs/design/43 预承诺规则（Δ质量>=+5% 且 Δ成本<=+20% 接线）")


if __name__ == "__main__":
    main()
