#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
UCSS v1.0 资源同步脚本（SYNC 模式核心工具，标准库实现，零第三方依赖）

子命令：
  sync                      在有网机器上拉取规则/漏洞库/工具包到本地缓存
  status                    查看缓存各资源版本与过期状态（消费端每日执行）
  pack                      将缓存打包为离线资源包 zip（内网摆渡用）
  import <pack.zip>         导入离线资源包（自动 sha256 校验）
  check-net                 探测外网连通性（用于模式判定）

环境变量：
  UCSS_CACHE_DIR   缓存目录，默认 ~/.ucss/cache
  UCSS_OFFLINE=1   强制离线（sync/pack 之外的操作不访问网络）

用法示例：
  python3 sync_resources.py sync
  python3 sync_resources.py status
  python3 sync_resources.py pack
  python3 sync_resources.py import ucss-offline-pack-20260908.zip
退出码：0=成功/正常，1=参数或环境错误，2=存在红线过期资源（status）
"""
import argparse, datetime, hashlib, json, os, shutil, subprocess, sys, urllib.request

DEFAULT_CACHE = os.path.expanduser("~/.ucss/cache")
CACHE = os.environ.get("UCSS_CACHE_DIR", DEFAULT_CACHE)
MANIFEST = os.path.join(CACHE, "manifest.json")
NOW = datetime.datetime.now()

# 同步矩阵：key -> (目标目录, 来源, 同步方式, 黄线天数, 红线天数)
MATRIX = {
    "semgrep-rules": ("semgrep-rules", "github.com/semgrep/semgrep-rules", "git", 30, 90),
    "trivy-db":      ("trivy-db",      "ghcr.io/aquasecurity/trivy-db",    "trivy", 7, 30),
    "advisories":    ("advisories",    "api.osv.dev",                      "osv", 7, 30),
    "wheels":        ("wheels",        "pypi.org",                         "pip", 90, 180),
    "npm-tarballs":  ("npm-tarballs",  "registry.npmjs.org",               "npm", 90, 180),
}


def load_manifest():
    if os.path.exists(MANIFEST):
        try:
            return json.load(open(MANIFEST, encoding="utf-8"))
        except Exception:
            pass
    return {"resources": {}}


def save_manifest(m):
    os.makedirs(CACHE, exist_ok=True)
    m["schema"] = "ucss-1.0"
    m["updated_at"] = NOW.isoformat(timespec="seconds")
    json.dump(m, open(MANIFEST, "w", encoding="utf-8"), ensure_ascii=False, indent=2)


def dir_hash(path):
    """目录内容聚合 sha256（文件名+内容）"""
    h = hashlib.sha256()
    for root, _, files in sorted(os.walk(path)):
        for f in sorted(files):
            fp = os.path.join(root, f)
            h.update(os.path.relpath(fp, path).encode())
            try:
                with open(fp, "rb") as fh:
                    for chunk in iter(lambda: fh.read(65536), b""):
                        h.update(chunk)
            except OSError:
                pass
    return h.hexdigest()[:16]


def which(cmd):
    return shutil.which(cmd) is not None


def run(cmd, **kw):
    r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, timeout=kw.pop("timeout", 600), **kw)
    return r.returncode, (r.stdout or "") + (r.stderr or "")


# ---------- 子命令：sync ----------
def do_sync(args):
    if os.environ.get("UCSS_OFFLINE") == "1":
        print("[stop] UCSS_OFFLINE=1，拒绝同步"); return 1
    m = load_manifest()
    results = []
    print(f"缓存目录: {CACHE}\n" + "=" * 56)

    # 1) Semgrep 规则（git clone --depth 1）
    dest = os.path.join(CACHE, "semgrep-rules")
    if which("git"):
        rc, out = (["SKIP"], "") if os.path.exists(dest) and args.skip_existing else \
                  run(["git", "clone", "--depth", "1",
                       "https://github.com/semgrep/semgrep-rules.git", dest])
        ok = rc == 0 or (os.path.exists(dest) and args.skip_existing)
        results.append(("semgrep-rules", ok, out.strip().splitlines()[-1:]))
    else:
        results.append(("semgrep-rules", False, ["git 不可用，跳过"]))

    # 2) Trivy 漏洞库
    if which("trivy"):
        rc, out = run(["trivy", "image", "--download-db-only",
                       "--cache-dir", os.path.join(CACHE, "trivy-db")], timeout=900)
        results.append(("trivy-db", rc == 0, out.strip().splitlines()[-1:]))
    else:
        results.append(("trivy-db", False, ["trivy 未安装，跳过"]))

    # 3) OSV advisory 批量导出（按项目 lock 文件中的包名查询）
    pkgs = set()
    for lock in args.locks or []:
        try:
            txt = open(lock, encoding="utf-8", errors="ignore").read()
            import re
            pkgs |= set(re.findall(r'^[a-zA-Z0-9_.\-]+(?=@|==|>=)', txt, re.M))
        except OSError as e:
            print(f"[warn] lock 文件不可读 {lock}: {e}")
    adv_dir = os.path.join(CACHE, "advisories")
    if pkgs:
        os.makedirs(adv_dir, exist_ok=True)
        ok_all = True
        for p in sorted(pkgs)[:200]:  # 上限防护
            try:
                url = f"https://api.osv.dev/v1/query"
                req = urllib.request.Request(url, data=json.dumps({"package": {"name": p}}).encode(),
                                             headers={"Content-Type": "application/json"})
                with urllib.request.urlopen(req, timeout=15) as resp:
                    data = resp.read()
                json.dump(json.loads(data), open(os.path.join(adv_dir, f"{p}.json"), "w"))
            except Exception as e:
                ok_all = False
                print(f"[warn] OSV 查询失败 {p}: {e}")
        results.append(("advisories", ok_all, [f"{len(pkgs)} 个包"]))
    else:
        results.append(("advisories", False, ["未提供 lock 文件，跳过（--locks a/requirements.txt …）"]))

    # 4) Python 工具 wheel
    if args.wheels and which("pip"):
        wdir = os.path.join(CACHE, "wheels"); os.makedirs(wdir, exist_ok=True)
        rc, out = run(["pip", "download", "-d", wdir] + args.wheels)
        results.append(("wheels", rc == 0, out.strip().splitlines()[-1:]))
    else:
        results.append(("wheels", False, ["未指定 --wheels，跳过"]))

    # 登记成功的资源
    for key, ok, notes in results:
        d = os.path.join(CACHE, MATRIX[key][0])
        if ok and (os.path.isdir(d) and os.listdir(d) or key == "wheels" and os.listdir(d)):
            m["resources"][key] = {"synced_at": NOW.isoformat(timespec="seconds"),
                                   "source": MATRIX[key][1], "sha256_16": dir_hash(d)}
    save_manifest(m)

    print("\n同步结果：")
    for key, ok, notes in results:
        mark = "✅" if ok else "⚠️ 跳过/失败"
        print(f"  {mark} {key:<14} {'; '.join(notes) if notes else ''}")
    print(f"\nmanifest 已更新: {MANIFEST}")
    return 0 if any(ok for _, ok, _ in results) else 1


# ---------- 子命令：status ----------
def do_status(_args):
    m = load_manifest()
    print(f"缓存目录: {CACHE}")
    print("=" * 56)
    worst = 0
    for key, (dirname, source, _, warn_d, red_d) in MATRIX.items():
        info = m["resources"].get(key)
        if not info:
            print(f"  ❌ {key:<14} 未同步（黄线 {warn_d}d / 红线 {red_d}d）")
            worst = max(worst, 1)
            continue
        age = (NOW - datetime.datetime.fromisoformat(info["synced_at"])).days
        state = "✅" if age <= warn_d else ("🟡 黄线" if age < red_d else "🔴 红线")
        worst = max(worst, 0 if age <= warn_d else (1 if age < red_d else 2))
        print(f"  {state} {key:<14} {info['synced_at'][:10]}（{age}d 前）  黄线{warn_d}d/红线{red_d}d")
    print("=" * 56)
    print("模式建议：" + ("OFFLINE 可用" if worst < 2 else "缓存红线过期：SCA 结论须标注『截至 {库版本}』"))
    return 2 if worst == 2 else 0


# ---------- 子命令：pack ----------
def do_pack(_args):
    if not os.path.exists(MANIFEST):
        print("[stop] 缓存为空，先执行 sync"); return 1
    packs = os.path.join(CACHE, "packs"); os.makedirs(packs, exist_ok=True)
    name = f"ucss-offline-pack-{NOW:%Y%m%d}.zip"
    out = os.path.join(packs, name)
    subprocess.run(["zip", "-rq", out + ".tmp", ".", "-i", "manifest.json",
                    "semgrep-rules/*", "trivy-db/*", "advisories/*", "wheels/*", "npm-tarballs/*"],
                   cwd=CACHE)
    os.replace(out + ".tmp", out)
    size = os.path.getsize(out) // 1024
    print(f"✅ 离线资源包: {out}（{size} KB）")
    return 0


# ---------- 子命令：import ----------
def do_import(args):
    if not os.path.exists(args.pack):
        print(f"[stop] 包不存在: {args.pack}"); return 1
    m = load_manifest()
    tmp = CACHE + ".import-tmp"
    shutil.rmtree(tmp, ignore_errors=True); os.makedirs(tmp)
    rc, out = run(["unzip", "-q", os.path.abspath(args.pack), "-d", tmp])
    if rc != 0:
        print(f"[stop] 解压失败: {out}"); return 1
    im = json.load(open(os.path.join(tmp, "manifest.json"), encoding="utf-8")) \
        if os.path.exists(os.path.join(tmp, "manifest.json")) else {"resources": {}}
    verified, failed = [], []
    for key, info in im.get("resources", {}).items():
        d = os.path.join(tmp, MATRIX[key][0])
        if os.path.isdir(d) and dir_hash(d) == info.get("sha256_16"):
            verified.append(key)
        else:
            failed.append(key)
    if failed:
        print(f"❌ 校验失败资源（拒绝导入）: {failed}"); shutil.rmtree(tmp); return 1
    for key in verified:
        dest = os.path.join(CACHE, MATRIX[key][0])
        shutil.rmtree(dest, ignore_errors=True)
        shutil.move(os.path.join(tmp, MATRIX[key][0]), dest)
        m["resources"][key] = dict(im["resources"][key], imported_at=NOW.isoformat(timespec="seconds"))
    save_manifest(m); shutil.rmtree(tmp, ignore_errors=True)
    print(f"✅ 导入并校验通过: {verified}"); return 0


# ---------- 子命令：check-net ----------
def do_check_net(_args):
    try:
        urllib.request.urlopen("https://api.osv.dev", timeout=2)
        print("ONLINE"); return 0
    except Exception:
        print("OFFLINE"); return 1


def main():
    ap = argparse.ArgumentParser(description="UCSS 资源同步工具（sync/status/pack/import/check-net）")
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("sync"); s.add_argument("--locks", nargs="*"); s.add_argument("--wheels", nargs="*"); s.add_argument("--skip-existing", action="store_true"); s.set_defaults(f=do_sync)
    sub.add_parser("status").set_defaults(f=do_status)
    sub.add_parser("pack").set_defaults(f=do_pack)
    i = sub.add_parser("import"); i.add_argument("pack"); i.set_defaults(f=do_import)
    sub.add_parser("check-net").set_defaults(f=do_check_net)
    args = ap.parse_args()
    return args.f(args)


if __name__ == "__main__":
    sys.exit(main())
