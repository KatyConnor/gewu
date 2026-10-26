#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
UCSS-Sync V1.0 —— OSV 离线快照同步（SCA 离线模式的数据资产生产者）
================================================================================
用法：python3 ucss-sync.py [--eco PyPI] [--out ~/.ucss/cache/osv-pypi.json]
流程：下载 OSV 生态全量 zip（在线执行）→ 提炼索引（去重/裁剪）→ 落盘版本化资产
产物：~/.ucss/cache/osv-pypi.json = {_snapshot_time, _advisories, advisories:[{id,summary,
      packages, ranges, versions}]}——pyscan.py sca 离线查询位
纪律：SCA 结论表述上限「截至 {_snapshot_time} 未发现已知漏洞」（UCSS 定本措辞）
外源数据资产模式（AEGIS 同款）：下载需在线，用例离线；落 ~/.ucss/cache/（规范缓存位，
与 osv-scanner 本地库同位）——git 与发布包均不跟踪（体积 17MB+·快照由用户在线窗口自产）
"""
import argparse
import io
import json
import os
import sys
import tempfile
import time
import zipfile

try:
    import urllib.request
except ImportError:
    print("错误：本命令需要 urllib（在线同步专用）", file=sys.stderr)
    sys.exit(1)

BASE = "https://osv-vulnerabilities.storage.googleapis.com/%s/all.zip"


def sync(eco, out_path):
    url = BASE % eco
    print("[1/4] 下载 %s ..." % url)
    with urllib.request.urlopen(url, timeout=120) as r:
        blob = r.read()
    print("      %d KB" % (len(blob) // 1024))

    print("[2/4] 解析 advisory ...")
    advisories, seen = [], set()
    with zipfile.ZipFile(io.BytesIO(blob)) as zf:
        names = [n for n in zf.namelist() if n.endswith(".json")]
        for n in names:
            try:
                v = json.loads(zf.read(n).decode("utf-8"))
            except (ValueError, UnicodeDecodeError):
                continue
            vid = v.get("id")
            if not vid or vid in seen:
                continue
            seen.add(vid)
            pkgs = set()
            ranges = []
            versions = []
            for aff in v.get("affected", []):
                pk = aff.get("package", {}).get("name")
                if pk and aff.get("package", {}).get("ecosystem", "").upper().startswith(eco.upper()):
                    pkgs.add(pk)
                    for rg in aff.get("ranges", []):
                        ranges.append({"events": rg.get("events", [])})
                    versions += aff.get("versions", [])
            if pkgs:
                advisories.append({
                    "id": vid,
                    "summary": (v.get("summary") or v.get("details", ""))[:100],
                    "packages": sorted(pkgs),
                    "ranges": ranges,
                    "versions": versions,
                })

    print("[3/4] 落盘 %d 条 advisory → %s" % (len(advisories), out_path))
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    doc = {
        "_snapshot_time": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "_ecosystem": eco,
        "_advisories": len(advisories),
        "advisories": advisories,
    }
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, separators=(",", ":"))
    size_kb = os.path.getsize(out_path) // 1024

    print("[4/4] 完成：%d 条 · %d KB · 快照时间 %s" % (
        len(advisories), size_kb, doc["_snapshot_time"]))
    print("SCA 结论上限：「截至 %s 未发现已知漏洞」" % doc["_snapshot_time"])
    print("验证：python3 pyscan.py sca requirements.txt --net offline")
    return 0


def main():
    ap = argparse.ArgumentParser(prog="ucss-sync",
                                 description="OSV 离线快照同步（在线执行·产离线资产）")
    ap.add_argument("--eco", default="PyPI", help="生态（默认 PyPI）")
    cache_dir = os.path.join(os.path.expanduser("~"), ".ucss", "cache")
    ap.add_argument("--out", default=os.path.join(cache_dir, "osv-pypi.json"))
    args = ap.parse_args()
    try:
        return sync(args.eco, args.out)
    except Exception as e:
        print("同步失败：%s（检查网络/内网请用已有快照）" % e, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
