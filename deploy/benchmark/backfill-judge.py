#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
补评脚本 — 对已落库的基准评测执行记录补跑 LLM-as-Judge 评分（S7 第 2 步）。

场景：跑批对话成功但评分步骤失败（如 judge 参数 bug），执行记录与输出已
在 agent_execution 中，本脚本按 experiment_group 分组补评分并出对比汇总。

用法:
  python3 backfill-judge.py --base-url http://localhost:8081/api \
      --user <账号> --password <密码> \
      --agents bench-legacy,bench-wenshi,bench-route_on
"""
import argparse
import json
import sys
import time
import urllib.request
import urllib.error
from datetime import datetime

TOKEN = {"value": None}


def http(method, path, body=None, timeout=300):
    url = ARGS.base_url.rstrip("/") + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    if body is not None:
        req.add_header("Content-Type", "application/json")
    if TOKEN["value"]:
        req.add_header("Authorization", f"Bearer {TOKEN['value']}")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:
            return json.loads(res.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body_txt = e.read().decode("utf-8", errors="replace")[:300]
        raise RuntimeError(f"HTTP {e.code} {method} {path}: {body_txt}") from None


def api(method, path, body=None, timeout=300):
    time.sleep(ARGS.sleep)
    res = http(method, path, body)
    if not res or res.get("code") != 10000:
        raise RuntimeError(f"{method} {path} 失败: {res}")
    return res["data"]


def main():
    global ARGS
    parser = argparse.ArgumentParser(description="补评 LLM-as-Judge 评分")
    parser.add_argument("--base-url", default="http://localhost:8081/api")
    parser.add_argument("--user", required=True)
    parser.add_argument("--password", required=True)
    parser.add_argument("--agents", default="bench-legacy,bench-wenshi,bench-route_on",
                        help="评测 Agent 名称列表（逗号分隔，其 modelConfig.experimentGroup 即分组）")
    parser.add_argument("--size", type=int, default=100, help="每组拉取的执行记录上限")
    parser.add_argument("--sleep", type=float, default=0.8)
    ARGS = parser.parse_args()

    data = api("POST", "/v1/auth/login",
               {"username": ARGS.user, "password": ARGS.password}, timeout=30)
    TOKEN["value"] = data["accessToken"]
    print("[1/3] 登录成功")

    # 找到各分组 Agent 的 id（experimentGroup = 分组名）
    page = api("GET", "/v1/agents?page=1&size=100")
    name_to_id = {a.get("agentName"): a.get("agentId") for a in (page.get("records") or [])}
    groups = [g.strip() for g in ARGS.agents.split(",") if g.strip()]

    all_rows = []
    done = 0
    print(f"[2/3] 开始补评（分组: {groups}）")
    for group in groups:
        agent_id = name_to_id.get(group)
        if not agent_id:
            print(f"  跳过 {group}: 未找到同名 Agent")
            continue
        page_data = api("GET", f"/v1/agents/executions/agent/{agent_id}?page=1&size={ARGS.size}")
        records = page_data.get("records") or []
        print(f"  {group}: 拉取执行记录 {len(records)} 条")
        for rec in records:
            if rec.get("status") != "completed" or not (rec.get("output") or "").strip():
                continue
            try:
                jr = api("POST", "/v1/evaluations/judge", {
                    "executionId": rec["executionId"],
                    "scenario": "benchmark",
                    "acceptanceCriteria": "回答应正确、完整、结构清晰地响应输入问题；"
                                          "如为代码题应给出可运行代码；如为方案题应给出合理拆解与理由。",
                    "output": rec["output"],
                }, timeout=300)
                all_rows.append({
                    "group": group,
                    "executionId": rec["executionId"],
                    "score": jr.get("totalScore"),
                    "verdict": (jr.get("verdict") or "")[:200],
                })
                done += 1
                if done % 10 == 0:
                    print(f"  已评分 {done} 条")
            except Exception as e:  # noqa: BLE001
                print(f"  评分失败 {rec['executionId']}: {str(e)[:120]}")

    print(f"[3/3] 补评完成，共 {done} 条")

    # 分组汇总
    summary = {}
    for r in all_rows:
        s = summary.setdefault(r["group"], {"n": 0, "scores": []})
        s["n"] += 1
        if r["score"] is not None:
            s["scores"].append(r["score"])
    print("\n== 分组评分汇总 ==")
    for g in sorted(summary):
        s = summary[g]
        avg = sum(s["scores"]) / len(s["scores"]) if s["scores"] else None
        print(f"  {g:12s} n={s['n']:3d} avgScore={avg if avg is None else round(avg, 3)}")

    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    out = f"{__file__.rsplit('/', 1)[0]}/judge-backfill-{stamp}.json"
    with open(out, "w", encoding="utf-8") as f:
        json.dump({"rows": all_rows, "summary": summary}, f, ensure_ascii=False, indent=2)
    print(f"明细: {out}")


if __name__ == "__main__":
    main()
