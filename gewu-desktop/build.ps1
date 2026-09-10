# ============================================================
# 格物平台桌面端 - 编译打包脚本（Windows PowerShell 5.1+）
# 产出 Windows 安装包（NSIS）与免安装版（portable），输出到 release\。
#
# 用法:
#   .\build.ps1                     # 默认打 Windows x64 包
#   .\build.ps1 -Arch all           # x64 + arm64
#   .\build.ps1 -Targets win,linux  # 交叉构建（Linux 包可跨平台）
#   .\build.ps1 -Dir                # 仅输出未打包目录（调试用）
# ============================================================
param(
    [string]$Targets = "win",
    [ValidateSet("x64", "arm64", "all", "")]
    [string]$Arch = "",
    [switch]$Dir
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

function Write-Info  { Write-Host "[INFO] $args" -ForegroundColor Green }
function Write-Warn2 { Write-Host "[WARN] $args" -ForegroundColor Yellow }
function Write-Err   { Write-Host "[ERROR] $args" -ForegroundColor Red }

# ------------------------------------------------------------
# 前置检查
# ------------------------------------------------------------
if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    Write-Err "未找到 node。请安装 Node.js 18+ 并加入 PATH。"
    exit 1
}

# ------------------------------------------------------------
# 依赖安装：有锁文件走 npm ci，否则 npm install
# ------------------------------------------------------------
if (-not (Test-Path "node_modules\electron")) {
    Write-Info "安装依赖（首次较慢，需下载 Electron 二进制）..."
    if (Test-Path "package-lock.json") {
        npm ci
    } else {
        npm install
    }
    if ($LASTEXITCODE -ne 0) { Write-Err "依赖安装失败"; exit 1 }
}

# ------------------------------------------------------------
# 组装 electron-builder 参数并打包
# ------------------------------------------------------------
$ebArgs = @()
foreach ($t in $Targets.Split(",")) {
    $ebArgs += "--$($t.Trim())"
}
if ($Arch) { $ebArgs += "--$Arch" }
if ($Dir)  { $ebArgs += "--dir" }
$ebArgs += @("--publish", "never")

Write-Info "开始打包: electron-builder $ebArgs"
npx electron-builder @ebArgs
if ($LASTEXITCODE -ne 0) { Write-Err "打包失败"; exit 1 }

Write-Info "===== 桌面端打包完成，产物目录: $PSScriptRoot\release ====="
