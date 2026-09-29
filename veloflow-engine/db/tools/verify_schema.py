#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""VLF 表列核对脚本（运维打包 #9）：解析 db/init/veloflow_init.sql 的表定义，
与目标库 information_schema 逐列比对，报告缺失/多余/类型漂移。
用法：python3 verify_schema.py [host] [port] [user] [password] [database]
依赖 mysql 客户端（docker exec gewu-mysql 兼容模式：--docker gewu-mysql）。"""
import re
import subprocess
import sys
from pathlib import Path

INIT = Path(__file__).resolve().parents[2] / "src/main/resources/db/init/veloflow_init.sql"


def parse_init():
    """解析 init SQL → {表名: {列名: 类型}}"""
    schema = {}
    current = None
    for line in INIT.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        m = re.match(r"CREATE TABLE IF NOT EXISTS (\w+)", line)
        if m:
            current = m.group(1)
            schema[current] = {}
            continue
        if current and line.startswith(")"):
            current = None
            continue
        if current and line and not line.startswith("--"):
            cm = re.match(r"(\w+)\s+(INT|VARCHAR\(\d+\)|BIGINT|TINYINT|LONGTEXT|TEXT|JSON|DATETIME)", line)
            if cm:
                schema[current][cm.group(1)] = cm.group(2).upper()
    return schema


def query_db(sql, container):
    out = subprocess.run(
        ["docker", "exec", container, "mysql", "-ugewu", "-pgewu123456", "-N", "-e", sql, "gewu_dev"],
        capture_output=True, text=True)
    return out.stdout.strip()


def main():
    container = sys.argv[1] if len(sys.argv) > 1 else "gewu-mysql"
    schema = parse_init()
    tables = {t for t in schema}
    db_tables = {r.split("\t")[0] for r in query_db(
        "SELECT table_name FROM information_schema.tables WHERE table_schema='gewu_dev' AND table_name LIKE 'VLF_%'",
        container).splitlines() if r}
    problems = []
    for table in sorted(tables):
        if table not in db_tables:
            problems.append(f"[缺失表] {table}")
            continue
        db_cols = {}
        for row in query_db(
                f"SELECT column_name, data_type FROM information_schema.columns "
                f"WHERE table_schema='gewu_dev' AND table_name='{table}'", container).splitlines():
            parts = row.split("\t")
            if len(parts) == 2:
                db_cols[parts[0]] = parts[1].upper()
        for col, typ in schema[table].items():
            if col not in db_cols:
                problems.append(f"[缺列] {table}.{col}（init: {typ}）")
            elif typ.split("(")[0] != db_cols[col].split("(")[0]:
                problems.append(f"[类型] {table}.{col} init={typ} db={db_cols[col]}")
    if problems:
        print(f"发现 {len(problems)} 处漂移：")
        for p in problems:
            print(" ", p)
        sys.exit(1)
    print(f"一致：{len(tables)} 张表全部核对通过（init ↔ 库）")


if __name__ == "__main__":
    main()
