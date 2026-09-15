#!/usr/bin/env bash
# 同步 Monaco Editor 静态资源到 public/monaco（自托管，免 CDN 依赖）。
# 在 monaco-editor 依赖升级后执行一次。
set -euo pipefail
cd "$(dirname "$0")/.."
rm -rf public/monaco/vs
cp -r node_modules/monaco-editor/min/vs public/monaco/vs
echo "Monaco 已同步到 public/monaco/vs（$(du -sh public/monaco/vs | cut -f1)）"
