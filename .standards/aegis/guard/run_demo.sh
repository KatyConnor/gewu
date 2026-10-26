#!/usr/bin/env bash
# AEGIS Guard 演示：违规场景应 BLOCK，合规场景应 PASS
set -e
cd "$(dirname "$0")"
python3 aegis_guard.py demo
