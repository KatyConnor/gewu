"""六 scanner 统一契约（技术方案 §3.1）：
scan(std_root, proj_root) -> dict | None
  产物域不存在 → None（视图自动隐藏该 Tab）
  单文件解析失败 → 跳过并收进 errors[]（降级不 FAIL）
  未知事件类型 → type:"other"（呈现现场，不打分）
"""
import os, json, re, hashlib
from datetime import datetime

def _exist(*paths):
    return os.path.exists(os.path.join(*paths)) if len(paths) > 1 else os.path.exists(paths[0])

def _read(path, limit=2_000_000):
    with open(path, encoding='utf-8', errors='replace') as f:
        return f.read(limit)

def _mtime(path):
    try:
        return datetime.fromtimestamp(os.path.getmtime(path)).strftime('%Y-%m-%d %H:%M')
    except OSError:
        return '?'

def _sha(path):
    try:
        return hashlib.sha256(open(path,'rb').read(2_000_000).hexdigest()[:12])
    except OSError:
        return '?'
